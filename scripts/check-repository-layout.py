#!/usr/bin/env python3
"""Check declared local boundaries; no build, request, install or deployment."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
layout = json.loads((ROOT / 'docs/development/repository-layout.json').read_text())
assert layout['schema_version'] == 1
paths = [layout[k] for k in ['native_host', 'native_project', 'native_entry',
         'backend_maintenance', 'backend_shared', 'deployed_snapshots',
         'public_design', 'public_research', 'live_status', 'current_goal', 'website']]
for key in ['backend_entries_present', 'swift_packages', 'frozen_app_roots',
            'historical_service_roots', 'upstream_sources', 'retained_input_roots']:
    paths.extend(layout[key])
for name in paths:
    path = Path(name)
    assert not path.is_absolute() and '..' not in path.parts, name
    assert (ROOT / path).exists(), name
declared_apps = {Path(layout['native_host']).name, *(Path(p).name for p in layout['frozen_app_roots'])}
website = ROOT / layout['website']
declared_apps.add(website.name)
assert (website / 'README.md').is_file(), 'Website stub missing'
assert not list(website.rglob('*.xcodeproj')), 'Website cannot become another native host'
if (website / 'package.json').is_file():
    pass
else:
    assert '已迁出' in (website / 'README.md').read_text(), 'Website build manifest missing'
web = ROOT / layout['web_client']
declared_apps.add(web.name)
assert (web / 'package.json').is_file(), 'Web client build manifest missing'
assert not list(web.rglob('*.xcodeproj')), 'Web client cannot become another native host'
assert 'family_v1' in layout['web_client_role']
mini = ROOT / layout['miniprogram_client']
declared_apps.add(mini.name)
assert (mini / 'package.json').is_file(), 'Miniprogram build manifest missing'
assert (mini / 'project.config.json').is_file(), 'Miniprogram project config missing'
assert not list(mini.rglob('*.xcodeproj')), 'Miniprogram cannot become another native host'
assert 'family_v1' in layout['miniprogram_client_role']
config_text = (mini / 'project.config.json').read_text()
assert '"touristappid"' in config_text, 'Miniprogram must use the WeChat tourist AppID in this batch'
assert 'appsecret' not in config_text.lower(), 'Miniprogram project config must not contain an AppSecret'
for package in layout['js_packages']:
    assert (ROOT / package / 'package.json').is_file(), package
    assert not (ROOT / package / 'Package.swift').exists(), package
actual_apps = {p.name for p in (ROOT / 'apps').iterdir() if p.is_dir() and not p.name.startswith('.')}
assert actual_apps == declared_apps, 'Undeclared App root; update reviewed layout roles first'
assert not (ROOT / 'CatCompanion').exists(), 'Second App skeleton cannot be imported as the active host'
project = (ROOT / layout['native_project'] / 'project.pbxproj').read_text()
for package in layout['swift_packages']:
    assert f'../../{package}' in project, package
    assert (ROOT / package / 'Package.swift').is_file(), package
    for source in (ROOT / package / 'Sources').rglob('*.swift'):
        text = source.read_text()
        assert not re.search(r'^\s*import\s+(cat|Supabase)\b', text, re.M), source
        assert 'URLSession' not in text, source
entries = [p for p in (ROOT / layout['native_host'] / 'Sources').rglob('*.swift')
           if re.search(r'@main\b', p.read_text())]
assert entries == [ROOT / layout['native_entry']], entries
for source in (ROOT / layout['native_host'] / 'Sources/Domain').glob('*.swift'):
    assert not re.search(r'^\s*import\s+(SwiftUI|UIKit)\b', source.read_text(), re.M), source
assert not layout['second_active_app_allowed']
assert not layout['component_owned_model_or_auth_allowed']
assert not layout['deployment_implied_by_checks']
print('PASS one active native entry, independent website stub, browser client, miniprogram skeleton, local Swift and JS packages, declared maintenance/history/source paths')
print('PASS Domain imports and package host/network boundaries; layout validation only')
