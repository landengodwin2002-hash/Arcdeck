# ArcDeck 1.4 phone checks

1. Install the matching 1.4 edition over 1.3 without uninstalling. Allow phone storage from Phone library; start/authorize Shizuku and connect native windows. Try denied access and disconnected/rebooted Shizuku; setup must remain usable and Open installed app normally must work.
2. Open Discord and ChatGPT. Confirm they are the installed apps and use their existing signed-in profiles. Select another accessible Android profile, where available. Test keyboard, voice typing, Back, scrolling, copy/paste, attachment pickers and in-app navigation. These require the actual phone and accounts.
3. Tile Discord, ChatGPT and YouTube in landscape; overlap/drag/resize, minimize/reopen, maximize another window, rotate and leave/return. Check focus and touch stay in the selected app. Close one native window without ending another. Try an unsupported/secure app; report failure instead of showing a website. Background audio and protected content remain app/account-dependent.
4. Discover existing phone games. Confirm ROMs appear in the matching PFP consoles and originals/saves remain in place. Test unique formats, ambiguous ISO/ZIP files, CUE/BIN/audio companions, M3U disc sets, removable storage and an opaque third-party document provider. Revoke a source and ensure its cached organization survives. Test cancellation/restart during a long directory scan.
5. Check automatically detected Papaya/GameHub external files and linked app spaces. Confirm namespaces for real Steam/local ID exports, correct launcher selection with multiple GameHub variants, repeat-import deduplication and source-file preservation. Unknown private databases must not be rewritten.
6. Launch a ROM/GameHub export from PFP with Shizuku connected. Confirm the actual emulator task appears inside the native panel and does not generate an external-foreground failure. Test missing emulator, URI permission loss and an app rejecting secondary displays. Specialized non-scalar launch intents/harvested launcher shortcuts retain normal Android launching.
7. Keep the original checks below for scripts, files, backups, spaces, original PFP features and recovery to another Home launcher. Measure CPU/memory with your actual simultaneous apps; JVM tests are not a device benchmark.

## Earlier feature checks

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

Use the PFP command bar with the normal Android keyboard and voice input. Paste a harmless Termux command, review it and check captured output. Test managed script pause/resume/stop and optional authenticated Automation as described in SCRIPT_CONNECTION.md. Native app background work remains Android-managed.
