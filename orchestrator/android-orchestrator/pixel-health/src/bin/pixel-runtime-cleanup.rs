#[path = "../runtime_cleanup.rs"]
mod runtime_cleanup;
#[path = "../ticket_lifecycle.rs"]
mod ticket_lifecycle;
#[path = "../management_health.rs"]
mod management_health;

fn main() {
    let code = match std::env::args().nth(1).as_deref() {
        Some(
            mode @ ("pixel-ticket-start.sh" | "pixel-ticket-stop.sh" | "pixel-ticket-health.sh"),
        ) => ticket_lifecycle::main(mode),
        Some("pixel-management-health.sh") => management_health::main(),
        _ => runtime_cleanup::main(),
    };
    std::process::exit(code);
}
