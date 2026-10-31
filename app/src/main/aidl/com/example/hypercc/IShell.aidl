package com.example.hypercc;

interface IShell {
    // Wajib ada untuk Shizuku UserService
    void destroy() = 16777114;

    String exec(String cmd) = 1;
    // true = matikan panel Quick Settings sistem (DISABLE2_QUICK_SETTINGS)
    String setQsDisabled(boolean disabled) = 2;
    // Disable the stock status-bar shade expansion (DISABLE_EXPAND).
    String setShadeDisabled(boolean disabled) = 3;
}
