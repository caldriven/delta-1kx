package com.alex.mfppro.ui

// Alpha terms shared by the Android and desktop apps, so accepting them on one counts the same version.

/** Bump when the terms change: everyone with a key is asked to accept again. */
const val TERMS_VERSION = 1
const val CREATOR = "Caldriven"
const val CREATOR_X = "https://x.com/caldriven"

const val ALPHA_ABOUT =
    "Delta 1kx is a mobile trading terminal in alpha testing. It is made to be used with MyFundedPerpetuals (MFP) accounts " +
        "through MFP's public API. Every feature is a work in progress: screens change between builds, things break, and " +
        "prices, PnL, drawdown, leverage, rule and risk figures can be wrong, late or missing. Always check MFP's own " +
        "dashboard before relying on a number."

const val NOT_AFFILIATED =
    "Delta 1kx is an independent app by $CREATOR. It is not made, endorsed, sponsored or supported by MyFundedPerpetuals " +
        "or by any exchange or data provider it reads from. Their names and logos belong to their owners and are used only " +
        "to show what the app connects to."

/** The points a tester agrees to when adding an API key. */
val TERMS_POINTS = listOf(
    "Delta 1kx is alpha software. Features are unfinished, can change or stop working, and data shown can be wrong.",
    "It has nothing to do with MyFundedPerpetuals (MFP). MFP does not make, check or support it.",
    "Orders you place here are real orders on your MFP account. Every trade and its outcome is your own decision and responsibility.",
    "Nothing in the app is financial advice. Leveraged trading can lose money fast, and a failed or breached account is on you.",
    "The app is provided as is, without any warranty. $CREATOR is not liable for losses, missed or wrong orders, missed alerts, " +
        "breached accounts or anything else that comes from using it.",
    "You got this build directly as an alpha tester, not from Google Play or another store, and you install and run it at your own risk.",
    "Your key is stored encrypted on this phone and is only sent to the MFP API.",
)
