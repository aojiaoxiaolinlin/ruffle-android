use android_activity::AndroidApp;
use jni::{jni_sig, jni_str};
use ruffle_frontend_utils::backends::navigator::NavigatorInterface;
use std::fs::File;
use std::path::Path;
use url::Url;

#[derive(Clone)]
pub struct AndroidNavigatorInterface {
    pub app: AndroidApp,
}

// TODO: Prompt the user for these things!
impl NavigatorInterface for AndroidNavigatorInterface {
    fn navigate_to_website(&self, url: Url) {
        // TODO: Implement `ask` behavior!
        // Route official login redirects back into the app instead of losing the
        // game session in an external browser. The Activity handles other links.
        let _ = crate::with_activity(&self.app, |env, activity| {
            let url = env.new_string(url.as_str())?;
            env.call_method(
                activity,
                jni_str!("navigateToUrl"),
                jni_sig!("(Ljava/lang/String;)V"),
                &[(&url).into()],
            )?;
            Ok::<_, jni::errors::Error>(())
        });
    }

    async fn open_file(&self, path: &Path) -> std::io::Result<File> {
        File::open(path)
    }

    async fn confirm_socket(&self, _host: &str, _port: u16) -> bool {
        true
    }
}
