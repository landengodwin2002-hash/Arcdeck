# ArcDeck 1.3 phone checks

1. Install the matching edition over 1.2 without uninstalling. Existing spaces, notes, games and folder permissions should remain.
2. In Spaces → Game connections, check that Papaya and the actual GameHub Lite variant are detected. A real spoof-name app such as AnTuTu must not be identified as GameHub.
3. Connect Papaya's existing ROM folder as Games / ROMs. Browse/search its files. Open the Papaya space and app, then use Import ROMs / PFP exports to add the same root in PFP Library Manager with its real platforms/emulators.
4. Use connected folder to attach that same directory to GameHub. Check that source bytes stay in place and tags/aliases apply across spaces. Connect separate saves/artwork folders too.
5. If GameHub exposes a file provider, select Connect GameHub provider and grant its desired app/files directory. Find saves / components, browse the found directories and pin them to the GameHub space. No game, save or component should be overwritten.
6. Review a folder containing real GameHub/Papaya-style `.steamappid`/`.localgameid` files. Verify names and IDs before import, choose the correct installed GameHub build, import selected games and launch through PFP's game detail. A same-number Steam ID and local ID must launch in their own namespace. Repeat the import and check no duplicate exact launch handles appear; tags and aliases survive.
7. Cancel folder selection, revoke a grant, restart/remove the source app or disconnect a folder. ArcDeck must show reconnect/missing status and retain other apps' folder connections and the actual source files. After backup restore on another installation, grant access again.
8. Test portrait and landscape, open/close the Game connections pane, return from the source app, and rotate while Android's folder picker is open. Check read-only providers and partial scans with a large directory. These steps require the owner's physical phone; automated tests cannot confirm its actual installed apps or files.

The automated suite covers the catalog, document operations, backups, callbacks and phone-sized Compose controls. These checks exercise your actual device, accounts, emulators and controller:

Update the signed 1.0 APK directly; keep its data and permissions. Test these additions first:

- Create Discord/Brave/ChatGPT spaces in WS → Spaces, connect their installed apps and attach library items. Add the same item to two spaces and verify one shared alias/tag set.
- Enable floating workspace in System and grant Android's display-over-other-apps permission. Open Discord/ChatGPT/Brave, move the WS bubble, open its panel, type a note, preview a file and minimize it. Check keyboard behavior in portrait and landscape. Stop it from the panel and notification.
- Share text and a disposable file from another app to Save to ArcDeck. Choose a space, save, close the source app and check that the imported file still previews. Share a file or context back to the connected app and confirm that its normal composer opens for your review.
- Try Open adjacent after entering Android split-screen through Recents. Device/app support varies; ordinary launch remains available.
- Open PFP features and visit the original Music/Video/Photos/Social/Settings screens, library manager and customization editors. Return to WS and confirm your space remains intact.
- Try Customize palettes, opacity/dim/text size at both endpoints, dock order and two saved layout profiles. Check Android status/navigation bars and the native Internet/volume panels.
- In Files, select disposable items, copy/cut and paste into another approved folder, apply a tag, preview images/text/PDF, and test batch deletion after confirmation. Check organization after moving a tagged file between roots.
- Export/restore a workspace backup with multiple app spaces and profiles. Imported file bytes are not embedded in organization backups; keep original files separately.

1. Install the signed Full or Lite APK and open it as an app before selecting it as Home. In System, verify Android's default-home picker can return you to the Motorola launcher.
2. Open WS in portrait and landscape. Try search, collections, Files, Notes, Terminal and System. Minimize, restore and resize windows; rotate and reopen the app to confirm layout and notes persist.
3. Connect your game library using PlayFieldPortal's existing settings. Find a game in Library, view its artwork, open its details and launch through its configured emulator or GameHub provider. Change a game favorite and confirm both surfaces agree.
4. Add a storage folder and index it. Open a search-result folder directly. Test copy, rename, share and delete with disposable test files. Revoke a folder's permission and confirm its previous tags/collections remain recoverable after reconnecting it.
5. Put the same game/app in two manual collections; reorder one and confirm the other is unchanged. Create a smart collection, edit its query and add/remove tags. Test aliases, pins and favorites.
6. Back up organization and notes, change a test collection, then restore. Confirm missing-source references retain their tags and memberships. Game files and Android permissions are deliberately not copied into the backup.
7. If you use Termux, grant its command permission, enable external apps and run `printf 'ArcDeck test\n'`. Confirm review, output and exit status. Try an interactive session and a denied-permission case.
8. Set a controller shortcut in System. Check D-pad focus, confirm, Back/B, workspace switching and the normal XMB controls. Check Full's Discord sign-in/voice features if used.
9. After these checks, optionally choose ArcDeck as default Home. Android Settings → Apps → Default apps → Home app remains available independently of ArcDeck.

The release has a different package identity from the earlier debug APK. Its private signing backup supports future updates to this release identity; it does not replace the official PlayFieldPortal signing key.

1.3 physical-phone checks: open the new PFP command bar with your usual Android keyboard and its microphone; paste a short harmless Termux command, review it and check captured output. Test the clipboard pad with ChatGPT/Discord. Verify your own service logins in their web windows; external-app authentication, voice calls and YouTube background play may require their native apps/account features.

Tile ChatGPT, Discord and Media side by side in landscape. Resize by the larger grip and sliders; scroll both dock and taskbar. Minimize/reopen each window, maximize another window, rotate the device, show/hide the keyboard and leave/return to ArcDeck. Expect unloaded pages to restore history, not unsaved form drafts. Check that media stops when its web window unloads. Native background audio remains controlled by that native app.

Start a disposable managed `sleep 120` command, pause/resume it, leave ArcDeck, reopen it, then stop the run. Clear finished history while another run is active and verify its controls remain. Only enable Automation if you want to test scripts; use the copied curl example, two successive window opens followed by tiling, and clipboard text. If testing optional accessibility, allow a disposable app and check tap/swipe/focused-field text; reject a different frontmost package and password input. Screen-off, notification Stop and Revoke token must end the connection. CPU, memory, real-site playback and device-specific keyboard behavior require profiling on the actual phone and are not claimed from JVM tests.
