# ArcDeck 1.1 phone checks

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
