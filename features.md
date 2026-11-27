# Features

## Current Features

- Safe Pay (v1.0.0)
  - Stops large `/pay` transactions before they are sent.
  - Configurable threshold.
  - Clickable Accept/Decline chat buttons.

- Toggleable Chat Timestamps (v1.1.0)
  - Adds the time to every chat message.
  - Can be turned on or off.
  - Configurable time format (for example `HH:mm`, `HH:mm:ss` or `hh:mm a`).
  - Configurable color and bracket style.
  - Hover a timestamp to see the full date.
  - Live preview in the settings screen.

- 2x Payment Payback (v1.2.0)
  - Pays back players who paid you, at 2x by default (configurable).
  - `/payback` starts and stops waiting for payments, `/payback start` pays everyone back, and `/payback stop` cancels payments in progress.
  - Also works with a keybind (Options > Controls > Key Binds, unbound by default).
  - Asks for Confirm/Cancel before sending, and each payout goes through Safe Pay.
  - Optional minimum: a player's payments add up, and anyone under the minimum is never paid back (can also hide their payment messages).

- Auction (v1.3.0)
  - Open the Auction menu with a customizable keybind (Options → Controls → Key Binds, unbound by default).
  - The Items tab lets you search and browse all items and blocks, preview the selected item, set the minimum bid and auction length, then start or end an auction. You can also use the item you're holding.
  - The Settings tab includes options for the overlay, notifications, bid updates, timer color change, and result display duration.
  - Bids are based on total payments received from each player. Only payments that meet or exceed the minimum bid count.
  - An on-screen overlay shows the item, time remaining, top bidder, and total bids.
  - Auctions end exactly when the timer expires.
  - The selected item, auction length, and minimum bid are saved for future auctions.

- Session Tracker (v1.3.0)
  - Tracks all money earned while playing.
  - Shows total profit for the current session.
  - Optional on-screen overlay with customizable position.
  - Persistant through restarts to prevent accidental crashes that will reset your progress.
  - Ability to reset your session tracker any time you need.

## Upcoming Features

- Nothing else is planned.
- Might change up alot of gui to match one style, since its kinda inconsistent. (Version T.B.D)

---

**Have a suggestion or found a bug?**  
Open an issue on GitHub: https://github.com/EliteEnvixity/DonutPlus/issues
