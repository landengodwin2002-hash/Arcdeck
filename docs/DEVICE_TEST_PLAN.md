# ArcDeck 1.0 phone checks

The automated suite covers the catalog, document operations, backups, callbacks and phone-sized Compose controls. These checks exercise your actual device, accounts, emulators and controller:

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
