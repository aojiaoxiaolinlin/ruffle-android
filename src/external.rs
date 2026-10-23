use ruffle_core::context::UpdateContext;
use ruffle_core::external::{ExternalInterfaceProvider, Value};

/// Page hooks used by the official Roco container. Never log call arguments (tickets).
pub struct RocoExternalInterface;

impl ExternalInterfaceProvider for RocoExternalInterface {
    fn call_method(&self, _context: &mut UpdateContext<'_>, name: &str, _args: &[Value]) -> Value {
        match name {
            "get_platfrom_src" => Value::String("2".to_owned()),
            _ => Value::Undefined,
        }
    }

    fn on_callback_available(&self, _name: &str) {}

    fn get_id(&self) -> Option<String> {
        Some("angelMain".to_owned())
    }
}
