"""Check specification references and scoped execution records, offline."""

import argparse
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
DIRECTORY = Path('docs/traceability')
SPECS = 'docs/specifications/'
SOURCE_PATHS = {
    'requirements': SPECS + '01_requirements.md',
    'basic': SPECS + '02_basic_design.md',
    'detail': SPECS + '03_detailed_design.md',
    'features': SPECS + '05_feature_coverage.md',
    'decisions': SPECS + '06_decisions.md',
    'quality': SPECS + '09_private_completion_review.md',
}
ID = r'(?:FR|NFR)-[A-Z0-9]+-\d+|DD-[A-Z0-9]+(?:-\d+)?|TEST-[A-Z0-9]+-\d+|F-\d+-\d+(?:\.\d+)?|(?:QLT|ADR|Q|R|P|IMP|UAT)-\d+'
REFERENCE = re.compile(r'(?<![A-Z0-9-])(' + ID + r')((?:(?:/|〜)\d+(?:\.\d+)?)*)')


def references(text):
    result = set()
    for match in REFERENCE.finditer(text):
        identifier, suffix = match.groups()
        result.add(identifier)
        prefix, number = identifier.rsplit('-', 1)
        for separator, end in re.findall(r'(/|〜)(\d+(?:\.\d+)?)', suffix):
            if separator == '〜':
                result.update(f'{prefix}-{n:02d}' for n in range(int(number) + 1, int(end) + 1))
            else:
                result.add(f'{prefix}-{end}')
            number = end
    return sorted(result)


def family(identifier):
    return 'REQ' if identifier.startswith(('FR-', 'NFR-')) else identifier.split('-')[0]


def inventory(root):
    entries = {}

    def add(identifier, path, line, links=(), scope='CURRENT'):
        if identifier in entries:
            raise ValueError(f'duplicate definition: {identifier}')
        entries[identifier] = {'id': identifier, 'source': f'{path}#L{line}',
                               'scope': scope, 'links': ','.join(sorted(links))}

    for kind, path in SOURCE_PATHS.items():
        for line_number, line in enumerate((root / path).read_text(encoding='utf-8').splitlines(), 1):
            cells = [cell.strip().strip('`') for cell in line.split('|')[1:-1]]
            first = cells[0] if cells else ''
            status = cells[1] if len(cells) > 1 else ''
            if kind == 'requirements' and re.fullmatch(r'(?:FR|NFR)-[A-Z0-9]+-\d+|UAT-\d+', first):
                scope = 'EXCLUDED' if status == 'EXCLUDED' else 'CURRENT'
                add(first, path, line_number, scope=scope)
            elif kind == 'basic' and re.fullmatch(r'ADR-\d+', first):
                add(first, path, line_number)
            elif kind == 'detail':
                heading = re.match(r'^#{2,3} (DD-[A-Z0-9]+(?:-\d+)?)\b', line)
                if heading:
                    scope = 'RETIRED' if line.rstrip().endswith('（廃止）') else 'CURRENT'
                    add(heading[1], path, line_number, scope=scope)
                elif re.fullmatch(r'TEST-[A-Z0-9]+-\d+|IMP-\d+', first):
                    add(first, path, line_number)
                elif re.fullmatch(r'(?:FR|NFR)-[A-Z0-9]+-\d+', first):
                    if first not in entries or entries[first]['links']:
                        raise ValueError(f'duplicate or unknown trace row: {first}')
                    entries[first]['links'] = ','.join(references(' '.join(cells[1:])))
            elif kind == 'features':
                feature = re.match(r'\[(F-\d+-\d+(?:\.\d+)?)\]', first)
                if feature:
                    scope = 'EXCLUDED' if cells[-1].startswith('適用除外：') else 'CURRENT'
                    add(feature[1], path, line_number, references(' '.join(cells[2:])), scope)
                elif re.fullmatch(r'F-\d+-\d+(?:\.\d+)?', first):
                    add(first, path, line_number, scope='RETIRED')
            elif kind == 'decisions':
                heading = re.match(r'^### (Q-\d+)：', line)
                if heading:
                    add(heading[1], path, line_number)
                elif re.fullmatch(r'[RP]-\d+', first):
                    scope = 'PUBLIC' if status == 'PUBLICへ延期' else 'CURRENT'
                    add(first, path, line_number, scope=scope)
            elif kind == 'quality':
                quality = re.match(r'QLT-\d+', first)
                if quality:
                    add(quality[0], path, line_number, references(first.split('/', 1)[1]))
    return [entries[key] for key in sorted(entries)]


def validate_records(records, entries):
    if not isinstance(records, list):
        return ['results must be an array of scoped records']
    errors = []
    known = {entry['id']: entry for entry in entries}
    names = set()
    fields = {'case_id', 'requirement_ids', 'test_ids', 'commit', 'stage', 'outcome',
              'execution', 'permission', 'reason', 'evidence', 'limitations'}
    for record in records:
        name = record.get('case_id', '') if isinstance(record, dict) else ''
        if not isinstance(record, dict) or set(record) != fields:
            errors.append(f'{name}: missing or unknown record fields')
            continue
        if not isinstance(name, str) or not name.strip() or name in names:
            errors.append(f'{name}: case_id must be nonblank and unique')
        if isinstance(name, str):
            names.add(name)
        for field in ('reason', 'limitations'):
            if not isinstance(record[field], str) or not record[field].strip():
                errors.append(f'{name}: {field} must be explicit')
        for field, prefix in (('requirement_ids', ('FR-', 'NFR-')), ('test_ids', ('TEST-',))):
            values = record[field]
            if not isinstance(values, list) or not values or not all(
                isinstance(value, str) and value.startswith(prefix) and value in known
                and known[value]['scope'] == 'CURRENT' for value in values
            ):
                errors.append(f'{name}: {field} must reference current IDs')
        if isinstance(record['requirement_ids'], list) and isinstance(record['test_ids'], list):
            for requirement in record['requirement_ids']:
                if isinstance(requirement, str) and requirement in known:
                    allowed = known[requirement]['links'].split(',')
                    if not any(test in allowed for test in record['test_ids']):
                        errors.append(f'{name}: no related test for {requirement}')
            related_tests = {test for requirement in record['requirement_ids']
                             if isinstance(requirement, str) and requirement in known
                             for test in known[requirement]['links'].split(',')}
            if any(not isinstance(test, str) or test not in related_tests for test in record['test_ids']):
                errors.append(f'{name}: test has no related requirement')
        if not isinstance(record['commit'], str) or not re.fullmatch(r'[0-9a-f]{40}', record['commit']):
            errors.append(f'{name}: commit must be a full SHA')
        if record['stage'] not in ('CURRENT', 'PUBLIC'):
            errors.append(f'{name}: unknown stage')
        if record['outcome'] not in ('PASS', 'FAIL', 'NOT_RUN', 'BLOCKED', 'NOT_APPLICABLE'):
            errors.append(f'{name}: unknown outcome')
        if record['execution'] not in ('EXECUTED', 'NOT_RUN', 'UNESTABLISHED'):
            errors.append(f'{name}: unknown execution')
        if record['permission'] not in ('GRANTED', 'NOT_REQUIRED', 'UNKNOWN', 'DENIED'):
            errors.append(f'{name}: unknown permission')
        evidence = record['evidence']
        if not isinstance(evidence, list) or not all(isinstance(item, str) and item.strip() for item in evidence):
            errors.append(f'{name}: evidence must contain nonblank references')
        if record['outcome'] == 'PASS' and (
            record['execution'] != 'EXECUTED' or record['permission'] not in ('GRANTED', 'NOT_REQUIRED')
            or not evidence
        ):
            errors.append(f'{name}: PASS requires execution, permission and evidence')
    return errors


def check(root):
    try:
        entries = inventory(root)
    except ValueError as error:
        return [str(error)]
    errors = []
    known = {entry['id']: entry for entry in entries}
    for entry in entries:
        links = entry['links'].split(',') if entry['links'] else []
        for link in links:
            if link not in known:
                errors.append(f"{entry['id']}: missing target {link}")
            elif known[link]['scope'] == 'RETIRED':
                errors.append(f"{entry['id']}: retired target {link}")
        if family(entry['id']) in ('REQ', 'F') and entry['scope'] == 'CURRENT':
            if not any(link.startswith('DD-') for link in links) or not any(link.startswith('TEST-') for link in links):
                errors.append(f"{entry['id']}: missing design/test link")
            if family(entry['id']) == 'F' and not any(link.startswith(('FR-', 'NFR-')) for link in links):
                errors.append(f"{entry['id']}: missing requirement link")
    records = json.loads((root / DIRECTORY / 'results.json').read_text(encoding='utf-8'))
    errors.extend(validate_records(records, entries))
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    args = parser.parse_args()
    try:
        errors = check(args.root)
    except (ValueError, KeyError, TypeError, OSError) as error:
        errors = [str(error)]
    for error in errors:
        print(error, file=sys.stderr)
    if not errors:
        print('Specification references and recorded results OK.')
    return bool(errors)


if __name__ == '__main__':
    sys.exit(main())
