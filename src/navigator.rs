use android_activity::AndroidApp;
use jni::{jni_sig, jni_str};
use ruffle_core::backend::navigator::{
    ErrorResponse, NavigationMethod, NavigatorBackend, OwnedFuture, Request, SuccessResponse,
};
use ruffle_core::indexmap::IndexMap;
use ruffle_core::loader::Error;
use ruffle_core::socket::{SocketAction, SocketHandle};
use ruffle_frontend_utils::backends::navigator::NavigatorInterface;
use std::fs::File;
use std::path::Path;
use std::time::Duration;
use url::{ParseError, Url};

#[derive(Clone)]
pub struct AndroidNavigatorInterface {
    pub app: AndroidApp,
}

impl AndroidNavigatorInterface {
    fn navigate(&self, url: &str) {
        // The Activity recognizes fixed login redirects; it never evaluates JS.
        let _ = crate::with_activity(&self.app, |env, activity| {
            let url = env.new_string(url)?;
            env.call_method(
                activity,
                jni_str!("navigateToUrl"),
                jni_sig!("(Ljava/lang/String;)V"),
                &[(&url).into()],
            )?;
            Ok::<_, jni::errors::Error>(())
        });
    }
}

// TODO: Prompt the user for these things!
impl NavigatorInterface for AndroidNavigatorInterface {
    fn navigate_to_website(&self, url: Url) {
        // TODO: Implement `ask` behavior!
        self.navigate(url.as_str());
    }

    async fn open_file(&self, path: &Path) -> std::io::Result<File> {
        File::open(path)
    }

    async fn confirm_socket(&self, _host: &str, _port: u16) -> bool {
        true
    }
}

/// Intercept the game's fixed JS login redirects before the generic backend
/// rejects javascript URLs. Fetches, sockets and all other navigation delegate.
pub struct KabuNavigatorBackend<N> {
    pub inner: N,
    pub interface: AndroidNavigatorInterface,
}

impl<N: NavigatorBackend> NavigatorBackend for KabuNavigatorBackend<N> {
    fn navigate_to_url(
        &self,
        url: &str,
        target: &str,
        vars_method: Option<(NavigationMethod, IndexMap<String, String>)>,
    ) {
        if url.starts_with("javascript:") {
            self.interface.navigate(url);
        } else {
            self.inner.navigate_to_url(url, target, vars_method);
        }
    }

    fn fetch(&self, request: Request) -> OwnedFuture<Box<dyn SuccessResponse>, ErrorResponse> {
        self.inner.fetch(request)
    }

    fn resolve_url(&self, url: &str) -> Result<Url, ParseError> {
        self.inner.resolve_url(url)
    }

    fn spawn_future(&mut self, future: OwnedFuture<(), Error>) {
        self.inner.spawn_future(future);
    }

    fn pre_process_url(&self, url: Url) -> Url {
        self.inner.pre_process_url(url)
    }

    fn connect_socket(
        &mut self,
        host: String,
        port: u16,
        timeout: Duration,
        handle: SocketHandle,
        receiver: async_channel::Receiver<Vec<u8>>,
        sender: async_channel::Sender<SocketAction>,
    ) {
        self.inner
            .connect_socket(host, port, timeout, handle, receiver, sender);
    }
}
