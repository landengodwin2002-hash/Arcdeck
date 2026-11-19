"""ArcDeck's explicit removal of upstream Social/coin features; preserves stored user data."""
from pathlib import Path


def plan_removals(repo: Path):
    result = {}

    def patch(relative, marker, edits):
        path = repo / relative
        if not path.is_file():
            return
        source = path.read_text(encoding='utf-8-sig')
        if marker not in source:
            for before, after in edits:
                if source.count(before) != 1:
                    raise RuntimeError(f'Legacy-feature preflight failed in {relative}: {before[:80]!r}')
                source = source.replace(before, after, 1)
            source = '// ' + marker + '\n' + source
        result[path] = source

    prefix = 'feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/'
    patch('core/core-data/src/main/kotlin/com/playfieldportal/core/data/repository/CategoryRepositoryImpl.kt',
          'ArcDeck removes legacy Social and Shiba category surfaces', [
        ('BUILT_IN_CATEGORIES.filter { it.id != BuiltInCategory.SOCIAL || discordSessionActivator.sdkAvailable }',
         'BUILT_IN_CATEGORIES.filterNot { it.id == BuiltInCategory.SOCIAL || it.id == BuiltInCategory.ACHIEVEMENTS }'),
        ('categoryEntities.map { it.toDomain() }',
         'categoryEntities.map { it.toDomain() }.filterNot { it.id == BuiltInCategory.SOCIAL || it.id == BuiltInCategory.ACHIEVEMENTS }'),
        ('categoryDao.observeAll().map { it.map { entity -> entity.toDomain() } }',
         'categoryDao.observeAll().map { it.map { entity -> entity.toDomain() }.filterNot { it.id == BuiltInCategory.SOCIAL || it.id == BuiltInCategory.ACHIEVEMENTS } }'),
    ])
    vm = repo / prefix / 'viewmodel/XMBViewModel.kt'
    if vm.is_file():
        source = vm.read_text(encoding='utf-8-sig')
        marker = 'ArcDeck filters legacy categories after fallback reconciliation'
        if marker not in source:
            old = '''            .let { cats ->
                if (discordAuthRepository.isDiscordAvailable()) cats
                else cats.filterNot { it.id == BuiltInCategory.SOCIAL }
            }'''
            if source.count(old) != 1: raise RuntimeError('Legacy XMB category preflight failed')
            source = source.replace(old, '            .filterNot { it.id == BuiltInCategory.SOCIAL || it.id == BuiltInCategory.ACHIEVEMENTS }')
            if source.count('SettingsSection.entries.map {') != 2: raise RuntimeError('Legacy settings sections preflight failed')
            source = source.replace('SettingsSection.entries.map {', 'SettingsSection.entries.filterNot { it == SettingsSection.ACHIEVEMENTS }.map {')
            old = 'internal fun isSettingsRouteEnabled(routeId: String): Boolean = routeId != "settings_backup" || FeatureFlags.BACKUP_RESTORE'
            new = 'internal fun isSettingsRouteEnabled(routeId: String): Boolean = !routeId.startsWith("settings_achievements") && (routeId != "settings_backup" || FeatureFlags.BACKUP_RESTORE)'
            if source.count(old) != 1: raise RuntimeError('Legacy settings-route preflight failed')
            source = source.replace(old, new)
            # Old deep links and controller focus cannot expose a hidden coin submenu.
            a = source.index('    SettingsSection.ACHIEVEMENTS -> listOf(')
            b = source.index('    SettingsSection.MEDIA -> ', a)
            source = source[:a] + '    SettingsSection.ACHIEVEMENTS -> emptyList()\n' + source[b:]
            source = '// ' + marker + '\n' + source
        result[vm] = source
    patch(prefix + 'viewmodel/GameContextMenuItems.kt', 'ArcDeck removes the coin game action', [
        ('''        if (item.isRealGame && item.platformId != XMBViewModel.ANDROID_PLATFORM_ID) {
            add(XMBContextMenuItem("view_shiba_coins", "View Shiba Coins"))
        }''', '        // Legacy coin action removed.'),
    ])
    screen = repo / prefix / 'ui/detail/GameDetailScreen.kt'
    if screen.is_file():
        source = screen.read_text(encoding='utf-8-sig'); marker = 'ArcDeck removes the legacy coin detail row'
        if marker not in source:
            a = source.index('        // ── Shiba Coins (never for Android entries')
            b = source.index('        // ── Overview', a)
            source = '// ' + marker + '\n' + source[:a] + source[b:]
        result[screen] = source
    patch(prefix + 'ui/detail/GameDetailViewModel.kt', 'ArcDeck removes coin keyboard focus', [
        ('showCoins   = loaded && game.platformId != ANDROID_PLATFORM_ID,', 'showCoins   = false,'),
    ])
    credentials = 'core/core-data/src/main/kotlin/com/playfieldportal/core/data/achievement/AchievementCredentialsProvider.kt'
    patch(credentials, 'ArcDeck disables legacy coin tracking and game-conversion workers', [
        ('context.pfpDataStore.data.map { it[KEY_ENABLED] ?: false }', 'context.pfpDataStore.data.map { false }'),
        ('context.pfpDataStore.data.map { it[KEY_LOCAL_STEAM_ENABLED] ?: false }', 'context.pfpDataStore.data.map { false }'),
        ('context.pfpDataStore.data.map { it[KEY_GOLDBERG_INSTALLER] ?: false }', 'context.pfpDataStore.data.map { false }'),
        ('context.pfpDataStore.data.first()[KEY_LOCAL_STEAM_ENABLED] ?: false', 'false'),
        ('context.pfpDataStore.data.first()[KEY_GOLDBERG_INSTALLER] ?: false', 'false'),
    ])
    patch('feature/feature-settings/src/main/kotlin/com/playfieldportal/feature/settings/viewmodel/SetupSteps.kt',
          'ArcDeck omits legacy coin onboarding', [
        ('''            SetupStep.LOCAL_ACHIEVEMENTS ->
                availability.vita3K || availability.armsx3 || availability.x360Mobile || availability.xenDroid''',
         '            SetupStep.ACHIEVEMENTS, SetupStep.LOCAL_ACHIEVEMENTS -> false'),
    ])
    full = repo / 'app/src/full/kotlin/com/playfieldportal/launcher/discord/FullDiscordBootstrap.kt'
    lite = repo / 'app/src/lite/kotlin/com/playfieldportal/launcher/discord'
    if full.is_file():
        for name in ('DiscordLiteModule.kt', 'NoOpDiscordSessionActivator.kt'):
            source = lite / name
            if not source.is_file(): raise RuntimeError('Missing upstream no-SDK binding: ' + name)
            destination = full if name == 'DiscordLiteModule.kt' else full.with_name(name)
            result[destination] = '// ArcDeck uses the existing no-SDK bindings in both update identities.\n' + source.read_text(encoding='utf-8-sig')
    return result
