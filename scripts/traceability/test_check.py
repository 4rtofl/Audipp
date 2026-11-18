import copy
from pathlib import Path
import shutil
import tempfile
import unittest

from scripts.traceability.check import (
    DIRECTORY, ROOT, SOURCE_PATHS, check, inventory, references, validate_records,
)


class InventoryTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for path in SOURCE_PATHS.values():
            destination = self.root / path
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / path, destination)
        shutil.copytree(ROOT / DIRECTORY, self.root / DIRECTORY)

    def change(self, path, before, after):
        target = self.root / path
        text = target.read_text(encoding='utf-8')
        self.assertIn(before, text)
        target.write_text(text.replace(before, after, 1), encoding='utf-8')

    def test_current_specifications_and_recorded_results_are_valid(self):
        self.assertEqual(check(self.root), [])

    def test_requirements_can_change_without_a_separate_inventory(self):
        requirements = self.root / SOURCE_PATHS['requirements']
        detail = self.root / SOURCE_PATHS['detail']
        with requirements.open('a', encoding='utf-8') as stream:
            stream.write('\n| FR-NEW-01 | C/RB | New requirement |\n')
        with detail.open('a', encoding='utf-8') as stream:
            stream.write('\n| FR-NEW-01 | DD-MODEL | TEST-LIB-01 |\n')
        self.assertEqual(check(self.root), [])

    def test_prose_can_change_without_updating_source_hashes(self):
        self.change(SOURCE_PATHS['requirements'], '現在の完成目標', '現在の目標')
        self.assertEqual(check(self.root), [])

    def test_missing_link_and_missing_target_are_detected(self):
        self.change('docs/specifications/03_detailed_design.md',
                    '| `FR-SRC-01` | DD-MODEL / DD-SRC / DD-UI |',
                    '| `FR-SRC-01` | DD-NONEXISTENT |')
        self.assertTrue(any('missing target DD-NONEXISTENT' in error for error in check(self.root)))
        self.change('docs/specifications/03_detailed_design.md', 'DD-NONEXISTENT |', 'no design |')
        self.assertTrue(any('FR-SRC-01: missing design/test link' in error for error in check(self.root)))

    def test_scopes_are_read_from_source(self):
        entries = {entry['id']: entry for entry in inventory(self.root)}
        for identifier, scope in (
            ('FR-PLT-02', 'EXCLUDED'), ('F-13-03.01', 'EXCLUDED'),
            ('F-01-02.06', 'RETIRED'), ('DD-PROV-06', 'RETIRED'), ('P-01', 'PUBLIC'),
        ):
            with self.subTest(identifier=identifier):
                self.assertEqual(entries[identifier]['scope'], scope)

    def test_windows_line_endings_preserve_specification_references(self):
        for path in SOURCE_PATHS.values():
            target = self.root / path
            target.write_bytes(target.read_bytes().replace(b'\n', b'\r\n'))
        self.assertEqual(check(self.root), [])

    def test_new_retired_design_link_is_rejected(self):
        self.change('docs/specifications/03_detailed_design.md',
                    '| `FR-SRC-02` | DD-SRC / DD-DB |', '| `FR-SRC-02` | DD-PROV-06 |')
        self.assertTrue(any('retired target' in error for error in check(self.root)))

    def test_duplicate_definitions_are_rejected(self):
        path = self.root / 'docs/specifications/01_requirements.md'
        with path.open('a') as stream:
            stream.write('\n| FR-SRC-01 | duplicate |\n')
        with self.assertRaisesRegex(ValueError, 'duplicate definition'):
            inventory(self.root)

    def test_shorthand_references_keep_all_targets(self):
        self.assertEqual(references('TEST-AUD-01/02/03 FR-SRC-08〜10 NFR-A11Y-01'),
                         ['FR-SRC-08', 'FR-SRC-09', 'FR-SRC-10', 'NFR-A11Y-01',
                          'TEST-AUD-01', 'TEST-AUD-02', 'TEST-AUD-03'])


class ResultTests(unittest.TestCase):
    def setUp(self):
        self.entries = inventory(ROOT)
        self.record = {
            'case_id': 'governance-inventory', 'requirement_ids': ['NFR-TEST-01'],
            'test_ids': ['TEST-GOV-01'], 'commit': 'a' * 40, 'stage': 'CURRENT',
            'outcome': 'PASS', 'execution': 'EXECUTED', 'permission': 'NOT_REQUIRED',
            'reason': 'Inventory mutation cases passed.', 'evidence': ['validation log'],
            'limitations': 'This case does not establish the complete TEST-GOV-01 family.',
        }

    def test_scoped_pass_accepts_evidence(self):
        self.assertEqual(validate_records([self.record], self.entries), [])

    def test_unrun_unestablished_and_unpermitted_cannot_pass(self):
        for field, value in (('execution', 'NOT_RUN'), ('execution', 'UNESTABLISHED'),
                             ('permission', 'UNKNOWN'), ('permission', 'DENIED'),
                             ('evidence', []), ('commit', 'main')):
            with self.subTest(field=field, value=value):
                record = copy.deepcopy(self.record)
                record[field] = value
                self.assertTrue(validate_records([record], self.entries))

    def test_non_success_reasons_can_be_saved(self):
        for outcome, execution, permission in (
            ('NOT_RUN', 'NOT_RUN', 'UNKNOWN'), ('BLOCKED', 'UNESTABLISHED', 'DENIED'),
            ('FAIL', 'EXECUTED', 'NOT_REQUIRED'), ('NOT_APPLICABLE', 'NOT_RUN', 'NOT_REQUIRED'),
        ):
            with self.subTest(outcome=outcome):
                record = dict(self.record, outcome=outcome, execution=execution, permission=permission, evidence=[])
                self.assertEqual(validate_records([record], self.entries), [])

    def test_unknown_excluded_and_unrelated_ids_are_rejected(self):
        for field, value in (('requirement_ids', ['FR-PLT-02']), ('test_ids', ['TEST-UNKNOWN-01']),
                             ('test_ids', ['TEST-AUD-01']), ('test_ids', ['TEST-GOV-01', 'TEST-AUD-01']),
                             ('requirement_ids', [])):
            with self.subTest(field=field, value=value):
                record = dict(self.record, **{field: value})
                self.assertTrue(validate_records([record], self.entries))

    def test_empty_object_and_duplicate_records_are_rejected(self):
        self.assertTrue(validate_records({}, self.entries))
        self.assertTrue(validate_records([self.record, self.record], self.entries))


if __name__ == '__main__':
    unittest.main()
