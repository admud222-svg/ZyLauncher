# Zy Launcher: libdiscord_partner_sdk.so stand-in needed by the prebuilt libgxcore.so.
add_library(discord_partner_sdk SHARED discord_stub.cpp)
target_compile_options(discord_partner_sdk PRIVATE -O2 -fno-exceptions -fno-rtti)
