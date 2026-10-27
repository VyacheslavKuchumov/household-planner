#!/usr/bin/env python3
"""Render the Mermaid ER model as Graphviz SVG/PNG without duplicating its entities."""
from pathlib import Path
import html
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
source = (ROOT / 'docs/database-erd.md').read_text()
model = source.split('```mermaid', 1)[1].split('```', 1)[0]
entities, relations, current = {}, [], None
for raw in model.splitlines():
    line = raw.strip()
    if not line or line == 'erDiagram':
        continue
    if line.endswith('{'):
        current = line[:-1].strip()
        entities[current] = []
    elif line == '}':
        current = None
    elif current:
        parts = line.split('"', 1)[0].split()
        if len(parts) >= 2:
            entities[current].append(parts)
    else:
        match = re.fullmatch(r'([A-Z_]+)\s+(\S+)\s+([A-Z_]+)\s*:\s*(.+)', line)
        if match:
            relations.append(match.groups())

def label(name, rows):
    out = [f'<TABLE BORDER="0" CELLBORDER="1" CELLSPACING="0" CELLPADDING="5"><TR><TD COLSPAN="2" BGCOLOR="#e4eee7"><B>{html.escape(name)}</B></TD></TR>']
    for kind, field, *keys in rows:
        value = field + (' ' + ' '.join(keys) if keys else '')
        out.append(f'<TR><TD ALIGN="LEFT">{html.escape(kind)}</TD><TD ALIGN="LEFT">{html.escape(value)}</TD></TR>')
    return ''.join(out) + '</TABLE>'

cardinality = {'||': '1', 'o|': '0..1', '|o': '0..1', 'o{': '0..*', '}o': '0..*', '|{': '1..*', '}|': '1..*'}
lines = ['digraph ER {',
         'graph [rankdir=LR,bgcolor="white",pad="0.3",nodesep="0.45",ranksep="0.9",splines=polyline,fontname="Arial",labelloc=t,label="Household Planner — ER diagram",fontsize=20];',
         'node [shape=plain,fontname="Arial",fontsize=10];',
         'edge [dir=none,color="#66756a",fontname="Arial",fontsize=9,labeldistance=2];']
for name, rows in entities.items():
    lines.append(f'{name} [label=<{label(name, rows)}>];')
for left, relation, right, name in relations:
    before, after = relation.split('--')
    lines.append(f'{left} -> {right} [label="{name}",taillabel="{cardinality[before]}",headlabel="{cardinality[after]}"];')
lines.append('}')
with tempfile.TemporaryDirectory(prefix='household-erd-') as temporary:
    dot = Path(temporary) / 'schema.dot'
    dot.write_text('\n'.join(lines))
    for extension in ['svg', 'png']:
        subprocess.run(['dot', '-T' + extension, str(dot), '-o', str(ROOT / f'docs/database-erd.{extension}')], check=True)
