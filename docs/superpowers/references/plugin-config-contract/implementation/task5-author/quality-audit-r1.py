from pathlib import Path
import json,hashlib,xml.etree.ElementTree as E,zipfile,re,subprocess
repo=Path('/Users/sunke/dev/ai-project/fibra'); root=Path('/private/tmp/fibra-config-contract-evidence/author'); fix=root/'r1-fix'; remote=root/'distribution/run/remote'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def load(p):return json.loads(p.read_text())
result={}
manifest=fix/'source-sha256.json'; inputs=load(manifest)
for x in inputs: assert sha(repo/x['path'])==x['sha256'],x
result['source_input']={'files':len(inputs),'manifest_sha256':sha(manifest),'HEAD':subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()}
result['reports']={}
for kind in ('internal','external'):
 rows=load(fix/(kind+'-reports.json')); totals={k:0 for k in ('tests','failures','errors','skipped')}; log=(fix/(kind+'.log')).read_text(); checked=[]
 for x in rows:
  p=fix/x['path']; assert sha(p)==x['sha256'],str(p)
  e=E.parse(p).getroot()
  for k in totals:assert e.attrib[k]==x[k];totals[k]+=int(e.attrib[k])
  assert len(e.findall('testcase'))==int(e.attrib['tests'])
  assert e.attrib['name'] in log
  checked.append({'file':str(p),'sha256':sha(p),'name':e.attrib['name'],'tests':e.attrib['tests']})
 assert 'BUILD SUCCESS' in log
 assert (fix/(kind+'.exit')).read_text().strip()=='0'
 result['reports'][kind]={'xml_count':len(rows),**totals,'xml':checked,'log_sha256':sha(fix/(kind+'.log'))}
full=list((root/'full-author-reports').rglob('TEST-*.xml')); total={k:0 for k in ('tests','failures','errors','skipped')}; log=(root/'full-author-final.log').read_text()
for p in full:
 e=E.parse(p).getroot()
 for k in total:total[k]+=int(e.attrib[k])
 assert e.attrib['name'] in log,e.attrib['name']
 assert len(e.findall('testcase'))==int(e.attrib['tests'])
 # Exact per-class result entry from original Maven execution.
 pat=r'Tests run: '+e.attrib['tests']+r', Failures: '+e.attrib['failures']+r', Errors: '+e.attrib['errors']+r', Skipped: '+e.attrib['skipped']+r'.*?'+re.escape(e.attrib['name'])
 assert re.search(pat,log),p
assert (root/'full-author-final.exit').read_text().strip()=='0'
result['reports']['full_original']={'xml_count':len(full),**total,'log_sha256':sha(root/'full-author-final.log')}
rows=load(fix/'external-input-sha256.json')
for x in rows:
 assert sha(fix/'consumer'/x['path'])==x['sha256']
 assert sha(repo/'fibra-distribution/src/test/consumers'/x['path'])==x['sha256']
result['external_inputs']=len(rows)
rows=load(fix/'resolved-fibra-jar-sha256.json')
for x in rows:
 for k in ('resolved','staged','published'):
  if k in x:assert sha(Path(x[k]))==x['sha256'],x[k]
result['external_jars']=len(rows)
classpath=set()
for x in load(fix/'external-reports.json'):
 e=E.parse(fix/x['path']).getroot()
 for p in e.findall('./properties/property'):
  if p.attrib['name']=='java.class.path':
   for item in p.attrib['value'].split(':'):
    assert str(repo) not in item,item
    if '/com/sstlfsj/' in item:classpath.add(item)
assert classpath=={x['resolved'] for x in rows if 'resolved' in x}
result['external_fibra_classpath_jars']=len(classpath)
archive=repo/'fibra-distribution/target/fibra-0.5.0-SNAPSHOT-bin.zip'; evidence=load(root/'artifact-bytes.json'); assert sha(archive)==evidence['zip_sha256']
with zipfile.ZipFile(archive) as z:
 for item in evidence['packages']:
  name=item['plugin']; source=next(repo.glob('fibra-plugins/*/'+name+'/src/plugin/fibra-config.json')); stage=repo/'fibra-distribution/target/fibra-0.5.0-SNAPSHOT/plugins'/name
  raw=source.read_bytes();assert sha(source)==item['schema_sha256'];assert sha(stage/'lib/main.jar')==item['final_staged_jar_sha256']
  with zipfile.ZipFile(stage/'lib/main.jar') as jar:
   entries=[x for x in jar.infolist() if x.filename=='META-INF/fibra/config.json'];assert len(entries)==1 and not entries[0].is_dir(); assert jar.read(entries[0])==raw
  assert (stage/'fibra-config.json').read_bytes()==raw
  assert z.read('fibra-0.5.0-SNAPSHOT/plugins/'+name+'/fibra-config.json')==raw
result['formal_bytes']={'packages':len(evidence['packages']),'zip_sha256':sha(archive)}
jars=list(remote.rglob('*.jar'));assert len(jars)==84
for p in jars:
 with zipfile.ZipFile(p) as z:assert not any('AuthorConfigurationQualification' in n for n in z.namelist()),p
result['published_helper_exclusion_jars']=len(jars)
generated=root/'distribution/run/generated/generated-plugin'; source=generated/'src/plugin/fibra-config.json'; raw=source.read_bytes();ev=load(root/'external-template-bytes.json');assert sha(source)==ev['schema_sha256']
for token in (b'"$schema"',b'"$defs"',b'"$ref"',b'#/$defs/message'):assert token in raw
assert b'${' not in raw and b'#[[' not in raw
installation=next((generated/'target').glob('*-plugin'))
assert (installation/'fibra-config.json').read_bytes()==raw
with zipfile.ZipFile(installation/'lib/plugin.jar') as z:assert z.read('META-INF/fibra/config.json')==raw
result['external_generated_template']={'schema_sha256':sha(source),'source_JAR_root_equal':True}
ev=load(fix/'publication-reuse.json');assert sha(repo/'fibra-plugin-archetype/.flattened-pom.xml')==ev['pom_sha256'];assert sha(repo/'fibra-plugin-archetype/target/fibra-plugin-archetype-0.5.0-SNAPSHOT.jar')==ev['jar_sha256']
for typ in ('pom','jar'):
 p=next((remote/'com/sstlfsj/fibra-plugin-archetype/0.5.0-SNAPSHOT').glob('*-1.'+typ));assert sha(p)==ev[typ+'_sha256']
result['publication_reuse']=ev
result['evidence_manifest_hashes']={str(p.relative_to(root)):sha(p) for p in [manifest,fix/'internal-reports.json',fix/'external-reports.json',fix/'external-input-sha256.json',fix/'resolved-fibra-jar-sha256.json',root/'artifact-bytes.json',root/'external-template-bytes.json',fix/'publication-reuse.json']}
(root/'quality-review-r1-receipt.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({k:v for k,v in result.items() if k not in ('reports','evidence_manifest_hashes')},indent=2));print({k:{a:b for a,b in v.items() if a!='xml'} for k,v in result['reports'].items()})
