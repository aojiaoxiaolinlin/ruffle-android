// Regenerate with the workspace's swf crate linked via rustc --extern swf=... .
// Two looping frames simulate a game's frame-driven network heartbeat.
use swf::*;

fn main() {
    let header = Header {
        compression: Compression::None,
        version: 6,
        stage_size: Rectangle {
            x_min: Twips::ZERO,
            x_max: Twips::from_pixels(320.0),
            y_min: Twips::ZERO,
            y_max: Twips::from_pixels(240.0),
        },
        frame_rate: Fixed8::from_f32(10.0),
        num_frames: 2,
    };
    // AVM1 Push(String), Trace, End.
    let text = b"background-heartbeat\0";
    let mut actions = vec![0x96];
    actions.extend_from_slice(&((text.len() + 1) as u16).to_le_bytes());
    actions.push(0);
    actions.extend_from_slice(text);
    actions.extend_from_slice(&[0x26, 0]);
    let tags = [
        Tag::DoAction(&actions),
        Tag::ShowFrame,
        Tag::DoAction(&actions),
        Tag::ShowFrame,
        Tag::End,
    ];
    write_swf(
        &header,
        &tags,
        std::fs::File::create("app/src/androidTest/res/raw/background_heartbeat.swf").unwrap(),
    )
    .unwrap();
}
