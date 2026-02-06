# OmniSync

This addon provides a collection of tools for website interaction, starting with permanent website muting.

## How to Use the Mute Functionality

1.  Navigate to a website you wish to mute.
2.  Click the OmniSync icon in the Firefox toolbar. The icon will change to a muted speaker, and the site will be muted.
3.  Any time you visit a page on that domain, it will be automatically muted.
4.  To unmute a site, navigate to it and click the toolbar icon again.

---

## Installation Guide

There are several ways to load and use OmniSync, depending on your needs.

### Method 1: Temporary Loading (for quick testing sessions)

This method is useful for quickly testing changes during active development, but **data (like your muted sites list) will be cleared when you close Firefox.**

1.  Open Firefox.
2.  In the address bar, type `about:debugging` and press Enter.
3.  In the left-hand menu, click on "**This Firefox**".
4.  Under the "Temporary Extensions" section, click the "**Load Temporary Add-on...**" button.
5.  In the file dialog that opens, navigate to the directory where you have these OmniSync files (the directory containing `manifest.json`, `background.js`, and `icons/`).
6.  Select the `manifest.json` file and click "Open".

The addon will now be installed and will remain active until you close Firefox. You will see its icon appear in the toolbar.

### Method 2: Permanent Private Installation (for long-term personal use)

This method allows you to install OmniSync permanently in a Firefox browser, preserving its data across restarts. **This requires Firefox Developer Edition or Firefox Nightly** and involves disabling a security feature.

**Prerequisites:**

1.  **Download Firefox Developer Edition:** [https://www.mozilla.org/firefox/developer/](https://www.mozilla.org/firefox/developer/)
2.  **Disable Signature Requirement:**
    *   Open Firefox Developer Edition.
    *   In the address bar, type `about:config` and press Enter.
    *   Accept the risk if prompted.
    *   Search for `xpinstall.signatures.required`.
    *   Toggle its value to `false`.
    *   *(Warning: Disabling this setting allows unsigned addons to be installed, which can pose a security risk if you install untrusted addons. Only do this for addons you trust completely.)*

**Installation Steps:**

1.  **Package the Addon:**
    *   Open your terminal or command prompt.
    *   Navigate to the `FireFoxAddon` directory (where `manifest.json` is located).
    *   Use a zip tool to create an archive of all the addon's files and folders (`manifest.json`, `background.js`, `icons/`). For example, you can use a command like:
        ```powershell
        Compress-Archive -Path manifest.json, background.js, icons -DestinationPath OmniSync.zip
        ```
    *   Rename the resulting `.zip` file to `.xpi`. For example, `OmniSync.zip` becomes `OmniSync.xpi`.

2.  **Install the .xpi:**
    *   Drag and drop the `OmniSync.xpi` file directly into your Firefox Developer Edition window.
    *   Firefox will prompt you to install the addon. Confirm the installation.

**Reinstallation/Updating:**

If you make changes to the addon's code:
1.  In Firefox Developer Edition, go to `about:addons`.
2.  Find "OmniSync" and click "Remove" or "Disable".
3.  Repeat the "Installation Steps" above with your newly packaged `.xpi` file.

### Method 3: Active Development (using web-ext for live reloading)

This is the most efficient method if you are actively developing and making frequent code changes, as it provides live reloading.

**Prerequisites:**

1.  **Install Node.js and npm:** If you don't have them, download and install from [https://nodejs.org/](https://nodejs.org/).
2.  **Install `web-ext`:** Open your terminal or command prompt and run:
    ```bash
    npm install -g web-ext
    ```

**Running the Addon:**

1.  Open your terminal or command prompt.
2.  Navigate to the `FireFoxAddon` directory.
3.  Run the addon using:
    ```bash
    web-ext run
    ```
    This will open a new Firefox instance with OmniSync loaded. Any changes you save to the addon's files will automatically reload the addon in this instance. Data will be persistent within this `web-ext` profile, but not in your main Firefox profile.
