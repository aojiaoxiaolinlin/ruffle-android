use crate::fonts::AndroidFonts;
use ruffle_core::backend::ui::{
    DialogResultFuture, FileFilter, FontDefinition, FullscreenError, LanguageIdentifier,
    MouseCursor, MultiDialogResultFuture, NullUiBackend, UiBackend,
};
use ruffle_core::font::FontQuery;
use url::Url;

pub struct AndroidUiBackend {
    fonts: AndroidFonts,
    fallback: NullUiBackend,
}

impl AndroidUiBackend {
    pub fn new() -> Self {
        Self {
            fonts: AndroidFonts::new(),
            fallback: NullUiBackend::new(),
        }
    }
}

impl UiBackend for AndroidUiBackend {
    fn load_device_font(&self, query: &FontQuery, register: &mut dyn FnMut(FontDefinition)) {
        self.fonts.load(query, register);
    }

    fn sort_device_fonts(
        &self,
        query: &FontQuery,
        register: &mut dyn FnMut(FontDefinition),
    ) -> Vec<FontQuery> {
        self.fonts.sort(query, register)
    }

    fn mouse_visible(&self) -> bool {
        self.fallback.mouse_visible()
    }
    fn set_mouse_visible(&mut self, visible: bool) {
        self.fallback.set_mouse_visible(visible);
    }
    fn set_mouse_cursor(&mut self, cursor: MouseCursor) {
        self.fallback.set_mouse_cursor(cursor);
    }
    fn clipboard_content(&mut self) -> String {
        self.fallback.clipboard_content()
    }
    fn set_clipboard_content(&mut self, content: String) {
        self.fallback.set_clipboard_content(content);
    }
    fn set_fullscreen(&mut self, is_full: bool) -> Result<(), FullscreenError> {
        self.fallback.set_fullscreen(is_full)
    }
    fn display_root_movie_download_failed_message(&self, invalid_swf: bool, error: String) {
        self.fallback
            .display_root_movie_download_failed_message(invalid_swf, error);
    }
    fn message(&self, message: &str) {
        self.fallback.message(message);
    }
    fn open_virtual_keyboard(&self) {
        self.fallback.open_virtual_keyboard();
    }
    fn close_virtual_keyboard(&self) {
        self.fallback.close_virtual_keyboard();
    }
    fn language(&self) -> LanguageIdentifier {
        self.fallback.language()
    }
    fn display_unsupported_video(&self, url: Url) {
        self.fallback.display_unsupported_video(url);
    }
    fn display_file_open_dialog(&mut self, filters: Vec<FileFilter>) -> Option<DialogResultFuture> {
        self.fallback.display_file_open_dialog(filters)
    }
    fn display_file_open_dialog_multiple(
        &mut self,
        filters: Vec<FileFilter>,
    ) -> Option<MultiDialogResultFuture> {
        self.fallback.display_file_open_dialog_multiple(filters)
    }
    fn display_file_save_dialog(
        &mut self,
        name: String,
        title: String,
    ) -> Option<DialogResultFuture> {
        self.fallback.display_file_save_dialog(name, title)
    }
    fn close_file_dialog(&mut self) {
        self.fallback.close_file_dialog();
    }
}
