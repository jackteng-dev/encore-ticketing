#!/usr/bin/env python3
"""Bounded GET-only GitHub/GHCR release verification; no deploy commands.

Artifacts are authenticated by the GitHub run + server supplied ZIP digest,
not by fields supplied inside release.json. No private-repo public transparency
log or Enterprise-only attestation feature is assumed.
"""
import hashlib
import io
import json
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request
import zipfile

OWNER = 'Elpsy-Deng'
REPOS = {'portfolio': ['developer-portfolio'], 'camp': ['fadaiting-camp'],
         'shop': ['shop_bak_ap','Shop_back'], 'encore': ['ticket-platform'], 'atlas': ['ai-knowledge-assistant']}
CI_PATH = {r: '.github/workflows/'+('ci.yml' if r=='developer-portfolio' else 'showcase-verify.yml')
           for repos in REPOS.values() for r in repos}
SERVICES = {'developer-portfolio': ['api','gateway'], 'fadaiting-camp': ['backend','gateway'],
            'shop_bak_ap':['api'], 'Shop_back':['gateway'], 'ticket-platform':['api-a','api-b','worker','gateway'],
            'ai-knowledge-assistant':['app']}
FENCES = {'developer-portfolio':['backend/src/main/resources/db/migration','infra','scripts/portfolio_deploy.py','scripts/portfolio_db.py'],
          'fadaiting-camp':['db/init','backend/src/main/resources/application.yml'],
          'shop_bak_ap':['scripts/demo-identity-schema.sql','src/main/resources'], 'Shop_back':['package-lock.json'],
          'ticket-platform':['backend/src/main/resources','infra','backend/src/main/java/dev/elpsy/ticket'],
          'ai-knowledge-assistant':['config','scripts/local-deploy.py','scripts/local-model-runtime.py','backend/src/main/resources']}


def require(value, message):
    if not value:
        raise ValueError(message)


def canonical(data):
    return json.dumps(data,sort_keys=True,separators=(',',':'),ensure_ascii=True).encode()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def unique_object(pairs):
    result={}
    for key,value in pairs:
        require(key not in result,'Duplicate JSON key')
        result[key]=value
    return result


def decode(raw):
    require(len(raw)<=2*1024*1024,'Bounded JSON required')
    return json.loads(raw,object_pairs_hook=unique_object)


def validate_artifacts(artifacts):
    require(isinstance(artifacts,dict) and 0<len(artifacts)<=3000,'Bounded immutable build artifact map required')
    for key,value in artifacts.items():
        require(isinstance(key,str) and len(key)<=1000 and re.match(r'^context-[a-z-]+/',key)
            and not any(x in key for x in ['\\','\x00','\n','\r']) and all(p not in ['.','..',''] for p in key.split('/'))
            and isinstance(value,str) and re.fullmatch('[0-9a-f]{64}',value),'Safe immutable build artifact path/hash required')
    return True


def artifact_json(raw, expected_digest):
    require(re.fullmatch('sha256:[0-9a-f]{64}',expected_digest or '') and 'sha256:'+sha(raw)==expected_digest,
            'GitHub authenticated artifact ZIP digest mismatch')
    require(len(raw)<=2*1024*1024,'Artifact too large')
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        info=archive.infolist()
        require(len(info)==1 and info[0].filename=='release.json' and info[0].file_size<=256*1024
                and not info[0].flag_bits&1 and not (info[0].external_attr>>16)&0o170000==0o120000,
                'Only one bounded regular release.json is allowed')
        return decode(archive.read(info[0]))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):
        return None


class GitHub:
    def __init__(self, token):
        self.token=token
        self.opener=urllib.request.build_opener(NoRedirect(),urllib.request.ProxyHandler({}))

    def get(self,path):
        require(path.startswith('/repos/'+OWNER+'/') or path.startswith('/users/'+OWNER+'/packages/container/'),
                'Only fixed owner repository/package GET endpoints')
        url='https://api.github.com'+path
        request=urllib.request.Request(url,headers={'Authorization':'Bearer '+self.token,
            'Accept':'application/vnd.github+json','X-GitHub-Api-Version':'2022-11-28','User-Agent':'swaggyserver-bounded-cd'})
        with self.opener.open(request,timeout=30) as response:
            return decode(response.read(2*1024*1024+1))

    def download(self,path):
        require(re.fullmatch('/repos/'+OWNER+r'/[A-Za-z0-9_-]+/actions/artifacts/[1-9][0-9]*/zip',path),
                'Fixed artifact endpoint required')
        request=urllib.request.Request('https://api.github.com'+path,headers={'Authorization':'Bearer '+self.token,
            'User-Agent':'swaggyserver-bounded-cd'})
        try:
            response=self.opener.open(request,timeout=30)
        except urllib.error.HTTPError as error:
            require(error.code in [302,303,307],'Artifact redirect required')
            location=error.headers.get('Location','');error.close()
            parsed=urllib.parse.urlsplit(location)
            require(parsed.scheme=='https' and parsed.hostname and not parsed.username and not parsed.password
                    and (parsed.hostname.endswith('.blob.core.windows.net') or parsed.hostname.endswith('.actions.githubusercontent.com')),
                    'Untrusted artifact redirect')
            # Explicit new request: NEVER send API credentials to a signed storage URL.
            response=self.opener.open(urllib.request.Request(location),timeout=60)
        with response:
            raw=response.read(2*1024*1024+1)
        require(len(raw)<=2*1024*1024,'Artifact download limit exceeded')
        return raw


def run_verified(api, repo, run_id, source, path):
    require(repo in CI_PATH and type(run_id) is int and run_id>0,'Allowlisted repo/run required')
    data=api.get('/repos/'+OWNER+'/'+repo+'/actions/runs/'+str(run_id))
    require(data.get('repository',{}).get('full_name')==OWNER+'/'+repo and
            data.get('head_repository',{}).get('id')==data.get('repository',{}).get('id') and
            data.get('head_sha')==source and data.get('head_branch')=='main' and data.get('status')=='completed' and
            data.get('conclusion')=='success' and data.get('path')==path and
            data.get('event') in (['push'] if path==CI_PATH[repo] else ['workflow_run','workflow_dispatch']),
            'Exact own main source/workflow/success required')
    return data


class PackageSourceEvidenceRequired(ValueError):
    pass


def private_package(api, package, repo, bootstrap=False, defer_to_oci=False):
    require(re.fullmatch('showcase-[a-z-]+',package or '') and repo in CI_PATH,'Fixed package required')
    try:
        value=api.get('/users/'+OWNER+'/packages/container/'+package)
    except urllib.error.HTTPError as error:
        if error.code==404 and bootstrap:
            return 'BOOTSTRAP_APPROVED'
        raise ValueError('Cannot prove private package visibility/access') from None
    require(value.get('visibility')=='private','Package must be PRIVATE')
    require(value.get('name')==package and value.get('package_type')=='container'
            and value.get('owner',{}).get('login','').casefold()==OWNER.casefold(),
            'Exact container package identity/owner required')
    linked=value.get('repository')
    if linked is not None:
        require(isinstance(linked,dict) and linked.get('full_name')==OWNER+'/'+repo,'Package links a different source repository')
    else:
        # GHCR's actual/documented REST shape can omit repository entirely.
        # Absence is not source proof. Explicit callers must verify the actual
        # immutable image OCI source/revision/recipe BEFORE activation/delivery.
        if not defer_to_oci:raise PackageSourceEvidenceRequired('GHCR HTTP200 private package omitted repository; immutable OCI source proof required')
        return 'PRIVATE_SOURCE_PENDING_OCI'
    return 'PASS'


def verify_component(api, component, policy):
    require(set(component)=={'schema','project','repository','source','ci_run','publisher_run','contract','images','recipe_sha256','artifacts'}
            and component['schema']==3,'Exact component schema required')
    project=component['project']; repo=component['repository']; source=component['source']
    require(project in REPOS and repo in REPOS[project] and re.fullmatch('[0-9a-f]{40}',source or ''),'Source/project mismatch')
    require(policy['repository']==repo and policy['project']==project,'Root policy mismatch')
    require(component['recipe_sha256']==policy['recipe_sha256'],'Unreviewed packaging recipe')
    validate_artifacts(component['artifacts'])
    metadata=api.get('/repos/'+OWNER+'/'+repo)
    require(metadata.get('private') is True and metadata.get('default_branch')=='main' and metadata.get('id')==policy['repository_id'],
            'Exact private repository identity required')
    require(api.get('/repos/'+OWNER+'/'+repo+'/branches/main')['commit']['sha']==source,'Stale source is not current main')
    run_verified(api,repo,component['ci_run'],source,CI_PATH[repo])
    run_verified(api,repo,component['publisher_run'],source,'.github/workflows/homelab-release.yml')
    tree=api.get('/repos/'+OWNER+'/'+repo+'/git/trees/'+source+'?recursive=1')
    require(not tree.get('truncated'),'Truncated source tree refused')
    entries={x['path']:x['sha'] for x in tree['tree']}
    require(entries.get('.github/workflows/homelab-release.yml')==policy['workflow_blob_sha']
            and entries.get(CI_PATH[repo])==policy['ci_blob_sha'], 'Unreviewed CI or publisher workflow')
    contract={p:entries.get(p) for p in FENCES[repo]}
    require(all(contract.values()) and component['contract']==contract and policy['contract']==contract,
            'Migration/protocol/config fence changed; explicit compatibility review required')
    require(set(component['images'])==set(SERVICES[repo]),'Fixed application services only')
    if repo=='ticket-platform':
        require(len({component['images'][s] for s in ['api-a','api-b','worker']})==1,'One Encore API/worker image required')
    for service,image in component['images'].items():
        package='showcase-'+project+'-'+('api' if repo=='ticket-platform' and service in ['api-a','api-b','worker'] else service)
        require(re.fullmatch('ghcr.io/elpsy-deng/'+package+'@sha256:[0-9a-f]{64}',image or '') and not image.endswith('0'*64),
                'Exact own package + immutable nonzero digest required')
        private_package(api,package,repo,defer_to_oci=True)
    return component


def verify_artifact(api,repo,run_id,policy):
    rows=api.get('/repos/'+OWNER+'/'+repo+'/actions/runs/'+str(run_id)+'/artifacts?per_page=100')
    require(rows['total_count']<=100,'Artifact pagination refused')
    found=[a for a in rows['artifacts'] if a['name']=='homelab-release']
    require(len(found)==1 and not found[0]['expired'] and found[0]['size_in_bytes']<=2*1024*1024,'Unique live release artifact required')
    artifact=found[0]
    raw=api.download('/repos/'+OWNER+'/'+repo+'/actions/artifacts/'+str(artifact['id'])+'/zip')
    component=artifact_json(raw,artifact.get('digest'))
    require(component['publisher_run']==run_id and component['repository']==repo,'Artifact/run association mismatch')
    return verify_component(api,component,policy)


def combine(project,components):
    require(set(c['repository'] for c in components)==set(REPOS[project]),'Complete per-repo release pair required')
    require(all(c['project']==project for c in components),'Cross-project component refused')
    images={}
    for c in components:
        require(not set(images)&set(c['images']),'Duplicate service')
        images.update(c['images'])
    return {'schema':2,'project':project,'sources':{OWNER+'/'+c['repository']:c['source'] for c in components},
            'images':images,'data_contract':sha(canonical({c['repository']:c['contract'] for c in components})),
            'ci_runs':{OWNER+'/'+c['repository']:'https://github.com/'+OWNER+'/'+c['repository']+'/actions/runs/'+str(c['ci_run']) for c in components}}


def verify_local_image(metadata,image,component,service):
    labels=metadata.get('Config',{}).get('Labels') or {}
    require(image in metadata.get('RepoDigests',[]) and metadata.get('Os')=='linux' and metadata.get('Architecture')=='amd64',
            'Pulled image digest/platform mismatch')
    require(labels.get('org.opencontainers.image.source')=='https://github.com/'+OWNER+'/'+component['repository'] and
            labels.get('org.opencontainers.image.revision')==component['source'] and
            labels.get('dev.elpsy.showcase.recipe')==component['recipe_sha256'], 'OCI source/revision/recipe provenance mismatch')
    require(metadata.get('Config',{}).get('User') in ['10001:10001','101:101'], 'Nonroot artifact required')
    return True
