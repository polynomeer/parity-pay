rootProject.name = "parity-pay"

include(
    "modules:shared-kernel",
    "modules:ledger",
    "modules:wallet",
    "modules:payment",
    "modules:settlement",
    "modules:reconciliation",
    "apps:pay-api",
)
