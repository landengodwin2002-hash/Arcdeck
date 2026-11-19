import json
from pathlib import Path
import unittest

from papaya_presets import compatible_recipes


class PapayaCompatibilityTests(unittest.TestCase):
    def test_existing_recipe_and_platform_choices_take_precedence(self):
        presets = {'emulatorApps': [
            {'packageName': 'test.existing', 'name': 'Different defaults', 'supportedPlatforms': ['psp']},
            {'packageName': 'test.new', 'name': 'New emulator', 'supportedPlatforms': ['3ds'],
             'launchConfiguration': {'explicitActivity': 'test.new.Play', 'fileParameterName': 'rom_uri'}},
        ]}
        result = compatible_recipes(presets, [{'packageNames': ['test.existing']}], {'psp', 'n3ds'})
        self.assertEqual(1, len(result))
        self.assertEqual(['n3ds'], result[0]['platformIds'])
        self.assertEqual({'rom_uri': '{rom_uri}'}, result[0]['launch']['extras'])
        self.assertTrue(result[0]['launch']['useSafUri'])

    def test_id_launches_spoofed_packages_and_cross_package_activities_are_not_rom_recipes(self):
        entries = [
            {'packageName': 'test.pc', 'name': 'PC', 'supportedPlatforms': ['win'], 'launchConfiguration': {'launchById': True}},
            {'packageName': 'com.miHoYo.Yuanshen', 'name': 'Spoof', 'supportedPlatforms': ['ns']},
            {'packageName': 'test.cross', 'name': 'Cross package', 'supportedPlatforms': ['psp'], 'launchConfiguration': {'explicitActivity': 'different.app.Player'}},
            {'packageName': 'test.integer', 'name': 'Integer', 'supportedPlatforms': ['psp'], 'launchConfiguration': {'fileParameterType': 'int'}},
        ]
        self.assertEqual([], compatible_recipes({'emulatorApps': entries}, [], {'windows', 'switch', 'psp'}))

    def test_identity_is_deterministic_and_cores_and_retired_entries_are_not_replaced(self):
        entry = {'packageName': 'test.one', 'name': 'One', 'supportedPlatforms': ['psp']}
        presets = {'emulatorApps': [entry, dict(entry), dict(entry, core='special'), dict(entry, flag='deprecated')]}
        first = compatible_recipes(presets, [], {'psp'})
        second = compatible_recipes(presets, [], {'psp'})
        self.assertEqual(first, second)
        self.assertEqual(1, len(first))

    def test_pinned_public_catalog_and_attribution_are_complete(self):
        root = Path(__file__).resolve().parents[2]
        assets = root / 'files/app/src/main/assets/arcdeck'
        catalog = json.loads((assets/'papaya-presets.json').read_text())
        self.assertEqual((52, 433, 14), tuple(len(catalog[k]) for k in ('platforms', 'emulatorApps', 'streamingApps')))
        self.assertIn('Sebastian', (assets/'papaya-LICENSE.txt').read_text())


if __name__ == '__main__':
    unittest.main()
