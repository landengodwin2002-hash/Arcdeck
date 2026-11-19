"""Adapt the compatible public Papaya recipes without replacing established PFP handlers."""
import hashlib
import json
import re
from pathlib import Path

PLATFORM_ALIASES = {
    'and': 'android', 'a26': 'atari2600', 'a52': 'atari5200', 'a78': 'atari7800',
    'lynx': 'atarilynx', 'arc': 'mame', 'sdc': 'dreamcast', 'neo': 'neogeo', 'pce': 'pcengine',
    'sgg': 'gamegear', 'sms': 'mastersystem', 'gen': 'megadrive', 'sat': 'saturn', '3ds': 'n3ds',
    'psv': 'psvita', 'ns': 'switch', 'win': 'windows', 'bws': 'wonderswan', 'nvb': 'virtualboy',
}

def compatible_recipes(presets, original, platform_ids):
    known = {pkg for entry in original for pkg in entry['packageNames']}
    additions = []
    seen = set()
    for entry in presets['emulatorApps']:
        pkg = entry['packageName']
        # Existing PFP and RetroArch/core detection remain authoritative. PC launch adapters
        # handle namespace, shortcut and launcher-generation rules separately.
        if pkg in known or entry.get('core') or entry.get('flag') == 'deprecated':
            continue
        if pkg.startswith(('com.miHoYo.', 'com.antutu.', 'com.ludashi.')):
            continue
        platforms = sorted({PLATFORM_ALIASES.get(p, p) for p in entry['supportedPlatforms']} & set(platform_ids))
        platforms = [p for p in platforms if p not in {'windows', 'android'}]
        launch = entry.get('launchConfiguration', {})
        if not platforms or launch.get('launchById') or launch.get('fileParameterType') == 'int':
            continue
        if not re.fullmatch(r'[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+', pkg):
            continue
        activity = launch.get('explicitActivity')
        if activity and (not activity.startswith(pkg + '.') or not re.fullmatch(r'[A-Za-z0-9_.$]+', activity)):
            continue
        recipe = {'intentType': 'COMPONENT' if activity else 'ACTION_VIEW',
                  'action': launch.get('action', 'android.intent.action.VIEW')}
        if activity:
            recipe['activityClass'] = activity
        absolute = launch.get('filePathType') == 'absolute'
        recipe['filesystemPathOnly'] = absolute
        recipe['useSafUri'] = not absolute
        parameter = launch.get('fileParameterName')
        if parameter:
            recipe['extras'] = {parameter: '{rom_path}' if absolute else '{rom_uri}'}
        elif activity:
            recipe['attachRomData'] = True
        for parameter in launch.get('additionalParameters', []):
            key = parameter['key']
            if 'booleanValue' in parameter:
                recipe.setdefault('boolExtras', {})[key] = parameter['booleanValue']
            elif 'stringValue' in parameter:
                recipe.setdefault('extras', {})[key] = parameter['stringValue']
            else:
                break
        else:
            identity = pkg + ':' + ','.join(platforms)
            if identity in seen:
                continue
            seen.add(identity)
            additions.append({'id': 'papaya_' + hashlib.sha256(identity.encode()).hexdigest()[:16],
                              'name': entry['name'], 'packageNames': [pkg], 'platformIds': platforms, 'launch': recipe})
    return additions

def plan_catalog(repo: Path, base: Path):
    kb = repo/'feature/feature-launcher/src/main/assets/emulator_kb/emulators.json'
    seed = repo/'core/core-data/src/main/kotlin/com/playfieldportal/core/data/database/seeder/PlatformSeeder.kt'
    if not kb.is_file() or not seed.is_file():
        return {}
    presets = json.loads((base/'files/app/src/main/assets/arcdeck/papaya-presets.json').read_text())
    if presets.get('presetSchemaVersion') != 4:
        raise RuntimeError('Unsupported Papaya catalog schema')
    content = json.loads(kb.read_text())
    original = [entry for entry in content['emulators'] if not entry['id'].startswith('papaya_')]
    platforms = set(re.findall(r'id\s*=\s*"([a-z0-9_-]+)"', seed.read_text()))
    additions = compatible_recipes(presets, original, platforms)
    content['emulators'] = original + additions
    content['version'] = max(content['version'], 3)
    content['label'] = 'built-in + Papaya compatible additions'
    report = {'source_commit': 'fbe96e0ea3c5100f65f31e397e8e23c1412a6b8e',
              'platforms': len(presets['platforms']), 'source_emulator_entries': len(presets['emulatorApps']),
              'streaming_apps': len(presets['streamingApps']), 'compatible_additions': additions,
              'policy': 'Existing PFP recipes, core discovery and PC launch adapters retain precedence; no platform extension lists broadened.'}
    return {kb: json.dumps(content, ensure_ascii=False, indent=2)+'\n',
            repo/'app/src/main/assets/arcdeck/papaya-adaptation.json': json.dumps(report, ensure_ascii=False, indent=2)+'\n'}
