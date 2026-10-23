use ruffle_core::context::UpdateContext;
use ruffle_core::external::{ExternalInterfaceProvider, Value};

/// Minimal page compatibility required by the game's return-to-login callbacks.
/// No JavaScript is evaluated, and call arguments (which can include tickets)
/// are never logged.
pub struct KabuExternalInterface;

impl ExternalInterfaceProvider for KabuExternalInterface {
    fn call_method(&self, _context: &mut UpdateContext<'_>, name: &str, _args: &[Value]) -> Value {
        match name {
            // Match the browser path instead of pretending to be the 4399 AIR client.
            "showBrowserIdentity" | "getUserAgent" => Value::String(String::new()),
            _ => Value::Undefined,
        }
    }

    fn on_callback_available(&self, _name: &str) {}

    fn get_id(&self) -> Option<String> {
        Some("GreenGame".to_owned())
    }
}
