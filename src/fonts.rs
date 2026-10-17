use fontdb::{Database, Family, Source, Style, Weight};
use ruffle_core::backend::ui::FontDefinition;
use ruffle_core::font::{FontFileData, FontQuery};
use std::cell::RefCell;
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;

// Android's Noto CJK collections contain separate regional faces. Prefer SC
// before the other variants so simplified Chinese uses the expected glyphs.
const CJK_FAMILIES: &[&str] = &[
    "Noto Sans CJK SC",
    "Noto Sans SC",
    "Noto Serif CJK SC",
    "Noto Serif SC",
    "Droid Sans Fallback",
    "Noto Sans CJK TC",
    "Noto Sans CJK JP",
    "Noto Sans CJK KR",
];

pub struct AndroidFonts {
    database: Database,
    files: RefCell<HashMap<PathBuf, Arc<Vec<u8>>>>,
}

impl AndroidFonts {
    pub fn new() -> Self {
        let mut database = Database::new();
        // fontdb's load_system_fonts does not scan Android. Include OEM and
        // updated system font locations as well as the standard system folder.
        for directory in [
            "/system/fonts",
            "/product/fonts",
            "/vendor/fonts",
            "/data/fonts/files",
        ] {
            database.load_fonts_dir(directory);
        }
        database.set_sans_serif_family("Roboto");
        database.set_serif_family("Noto Serif");
        database.set_monospace_family("Droid Sans Mono");
        log::info!("Found {} Android font faces", database.faces().count());
        Self {
            database,
            files: RefCell::new(HashMap::new()),
        }
    }

    fn candidates(&self, query: &FontQuery) -> Vec<fontdb::ID> {
        let generic = match query.lowercase_name.as_str() {
            "_serif" | "serif" | "times new roman" | "simsun" | "宋体" => Family::Serif,
            "_typewriter" | "monospace" | "courier new" => Family::Monospace,
            _ => Family::SansSerif,
        };
        let mut ids = Vec::new();
        for family in [
            Family::Name(&query.name),
            generic,
            Family::Name("Noto Sans"),
        ]
        .iter()
        .copied()
        .chain(CJK_FAMILIES.iter().map(|name| Family::Name(name)))
        {
            if let Some(id) = self.database.query(&fontdb::Query {
                families: &[family],
                weight: if query.is_bold {
                    Weight::BOLD
                } else {
                    Weight::NORMAL
                },
                style: if query.is_italic {
                    Style::Italic
                } else {
                    Style::Normal
                },
                ..Default::default()
            }) {
                if !ids.contains(&id) {
                    ids.push(id);
                }
            }
        }
        ids
    }

    fn definition(&self, id: fontdb::ID, name: String) -> Option<FontDefinition<'static>> {
        let face = self.database.face(id)?;
        let data = match &face.source {
            Source::Binary(data) | Source::SharedFile(_, data) => {
                FontFileData::new_shared(data.clone())
            }
            Source::File(path) => {
                let mut files = self.files.borrow_mut();
                let data = if let Some(data) = files.get(path) {
                    data.clone()
                } else {
                    let bytes = match std::fs::read(path) {
                        Ok(bytes) => Arc::new(bytes),
                        Err(error) => {
                            log::warn!("Cannot read Android font {}: {error}", path.display());
                            return None;
                        }
                    };
                    files.insert(path.clone(), bytes.clone());
                    bytes
                };
                FontFileData::new_shared(data)
            }
        };
        Some(FontDefinition::FontFile {
            name,
            is_bold: face.weight > Weight::NORMAL,
            is_italic: face.style != Style::Normal,
            data,
            index: face.index,
        })
    }

    pub fn load(&self, query: &FontQuery, register: &mut dyn FnMut(FontDefinition)) {
        for id in self.candidates(query) {
            if let Some(definition) = self.definition(id, query.name.clone()) {
                register(definition);
                break;
            }
        }
    }

    pub fn sort(
        &self,
        query: &FontQuery,
        register: &mut dyn FnMut(FontDefinition),
    ) -> Vec<FontQuery> {
        let mut result = Vec::new();
        for id in self.candidates(query) {
            let face = self.database.face(id).unwrap();
            let name = face.post_script_name.clone();
            if let Some(definition) = self.definition(id, name.clone()) {
                log::info!(
                    "Loading Android font {name} for {} (face {})",
                    query.name,
                    face.index
                );
                register(definition);
                result.push(FontQuery::new(
                    query.font_type,
                    name,
                    face.weight > Weight::NORMAL,
                    face.style != Style::Normal,
                ));
            }
        }
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ruffle_core::font::FontType;

    fn fonts() -> AndroidFonts {
        let mut database = Database::new();
        for (names, index) in [
            (vec!["Roboto", "Arial"], 0),
            (vec!["Noto Sans CJK JP"], 1),
            (vec!["Noto Sans CJK SC", "Noto Sans SC"], 2),
        ] {
            // Candidate selection and registration metadata do not parse glyphs.
            database.push_face_info(fontdb::FaceInfo {
                id: fontdb::ID::dummy(),
                source: Source::Binary(Arc::new(Vec::<u8>::new())),
                index,
                families: names
                    .iter()
                    .map(|name| (name.to_string(), fontdb::Language::English_UnitedStates))
                    .collect(),
                post_script_name: names[0].to_owned(),
                style: Style::Normal,
                weight: Weight::NORMAL,
                stretch: fontdb::Stretch::Normal,
                monospaced: false,
            });
        }
        database.set_sans_serif_family("Roboto");
        AndroidFonts {
            database,
            files: RefCell::new(HashMap::new()),
        }
    }

    #[test]
    fn preserves_primary_and_prefers_simplified_chinese_without_duplicates() {
        let fonts = fonts();
        let query = FontQuery::new(FontType::Device, "Arial".into(), true, true);
        let mut indices = Vec::new();
        let sorted = fonts.sort(&query, &mut |definition| {
            if let FontDefinition::FontFile { index, .. } = definition {
                indices.push(index);
            }
        });
        assert_eq!(indices, [0, 2, 1]);
        assert!(sorted.iter().all(|font| !font.is_bold && !font.is_italic));
    }

    #[test]
    fn missing_windows_family_gets_android_primary_and_cjk_fallback() {
        let fonts = fonts();
        let query = FontQuery::new(FontType::Device, "Microsoft YaHei".into(), false, false);
        let sorted = fonts.sort(&query, &mut |_| {});
        assert_eq!(sorted[0].name, "Roboto");
        assert!(sorted.iter().any(|font| font.name == "Noto Sans CJK SC"));
    }

    #[test]
    fn single_font_is_registered_under_requested_name() {
        let fonts = fonts();
        let query = FontQuery::new(FontType::Device, "Missing family".into(), false, false);
        let mut names = Vec::new();
        fonts.load(&query, &mut |definition| {
            if let FontDefinition::FontFile { name, .. } = definition {
                names.push(name);
            }
        });
        assert_eq!(names, ["Missing family"]);
    }
}
