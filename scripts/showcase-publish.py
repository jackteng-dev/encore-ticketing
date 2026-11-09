#!/usr/bin/env python3
"""GitHub-hosted component packaging. Private registry write is opt-in only.

Never SSH, host deployment, production credentials, model download or DB write.
Existing exact main CI is checked before packaging. Camp's deployment patch
changes compiled bytes, so its patched full suite is verified additionally.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

BASE=Path(__file__).resolve().parents[1]


def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path);value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value


T=module('cd_trust',BASE/'scripts/showcase-cd-trust.py')
# Repository owner from the Actions runtime, so an account rename needs no code change.
OWNER=os.environ.get('GITHUB_REPOSITORY_OWNER','Elpsy-Deng');T.OWNER=OWNER;REGISTRY=OWNER.lower()


def recipe_digest():
    paths=[BASE/'scripts/showcase-publish.py',BASE/'scripts/showcase-cd-trust.py',*sorted((BASE/'.homelab/release-assets').rglob('*'))]
    return T.sha(T.canonical({str(p.relative_to(BASE)):T.sha(p.read_bytes()) for p in paths if p.is_file()}))


def recipe(project,service,images):
    if service not in ['gateway','app','api','backend']:
        raise ValueError('Fixed packaging component required')
    if service=='gateway':
        return 'FROM '+images['nginx']['image']+'\nCOPY --chmod=0444 nginx.conf /etc/nginx/nginx.conf\nCOPY --chown=101:101 dist/ /usr/share/nginx/html/\nUSER 101:101\nENTRYPOINT ["nginx","-g","daemon off;"]\nCMD []\n'
    if project=='atlas':
        return ('FROM '+images['python']['image']+'\nRUN apt-get update && apt-get install -y --no-install-recommends libstdc++6 libgomp1 libssl3t64 libcurl4t64 zlib1g && rm -rf /var/lib/apt/lists/*\n'
            'WORKDIR /app\nCOPY --chmod=0755 java/ /opt/java/\nCOPY --chmod=0555 scripts/ /app/scripts/\nCOPY --chmod=0555 config/ /app/config/\nCOPY --chmod=0444 backend/ /app/backend/\n'
            'ENV JAVA_HOME=/opt/java AKA_JAVA_BIN=/opt/java/bin/java SERVER_ADDRESS=0.0.0.0\nUSER 10001:10001\n'
            'ENTRYPOINT ["python3","/app/scripts/local-deploy.py","run","--config","/app/config/homelab.json","--token","homelab-atlas-supervisor"]\n')
    runtime=images['java25' if project=='camp' else 'java17' if project=='shop' else 'java21']['image']
    return 'FROM '+runtime+'\nWORKDIR /app\nCOPY --chmod=0444 app.jar /app/app.jar\nUSER 10001:10001\nENTRYPOINT ["java","-Xmx320m","-XX:+UseSerialGC","-XX:ActiveProcessorCount=2","-jar","/app/app.jar"]\nCMD []\n'


def gateway(project):
    upstream='backend:9090/fadaiting-camp/api/' if project=='camp' else 'api:9090/ELPSY/api/'
    value='pid /tmp/nginx.pid;\nevents { worker_connections 256; }\nhttp { include /etc/nginx/mime.types; default_type application/octet-stream; client_body_temp_path /tmp/client; proxy_temp_path /tmp/proxy; fastcgi_temp_path /tmp/fastcgi; uwsgi_temp_path /tmp/uwsgi; scgi_temp_path /tmp/scgi; access_log off; server { listen 8080; absolute_redirect off; root /usr/share/nginx/html; client_max_body_size 32m; location = /healthz { return 200 "gateway\\n"; } location /api/ { proxy_pass http://'+upstream+'; proxy_set_header Host $http_host; proxy_set_header X-Forwarded-Proto ""; proxy_set_header X-Forwarded-For ""; proxy_set_header Forwarded ""; proxy_read_timeout 120s; } location / { try_files $uri $uri/ /index.html; } } }\n'
    if project=='camp':
        value=value.replace(' location / {',' location /objects/ { proxy_pass http://minio:9000/; proxy_set_header Host minio:9000; proxy_set_header Authorization ""; proxy_read_timeout 60s; } location / {')
    return value


def build(project,repo,out,source):
    assets=BASE/'.homelab/release-assets'
    images=json.loads((assets/'images.lock.json').read_text())['images']
    # Build tools/dependency lifecycle scripts never receive the registry/API token.
    clean_env={key:os.environ[key] for key in ['PATH','HOME','JAVA_HOME','LANG','LC_ALL','CI',
        'RUNNER_TEMP','GITHUB_ACTIONS','RUNNER_ENVIRONMENT','GITHUB_REPOSITORY','GITHUB_SHA','MAVEN_OPTS'] if key in os.environ}
    def command(argv,label,cwd=BASE,env=None,timeout=900):
        with (out/(label+'.log')).open('xb') as stream:
            p=subprocess.run(argv,cwd=cwd,env=env or clean_env,stdout=stream,stderr=subprocess.STDOUT,timeout=timeout)
        T.require(p.returncode==0,'Private packaging step failed: '+label)
    if repo!='shop_bak_ap':
        frontend=BASE if project=='portfolio' or repo=='Shop_back' else BASE/'frontend'
        command(['npm','ci'],'npm',frontend)
        command(['npm','run','build'],'vue',frontend)
    if repo!='Shop_back':
        if project=='camp':
            spec=json.loads((assets/'camp-patch.json').read_text());path=BASE/spec['path']
            T.require(T.sha(path.read_bytes())==spec['source_sha256'],'Camp patch exact original source mismatch')
            command(['git','apply','--check',str(assets/'camp-deployment.patch')],'patch-check')
            command(['git','apply',str(assets/'camp-deployment.patch')],'patch')
            T.require(T.sha(path.read_bytes())==spec['patched_sha256'],'Camp patch output mismatch')
            dest=BASE/'backend/src/test/java/com/fadaiting/camp/controller/MinioPublicUrlTest.java'
            T.require(not dest.exists(),'Preserve existing tests');dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes((assets/'MinioPublicUrlTest.java').read_bytes())
            fixture_module=module('ci_fixture',BASE/'scripts/homelab-ci.py')
            with tempfile.TemporaryDirectory(prefix='camp-release-fixture-') as tmp:
                fixture=fixture_module.Fixture(images,Path(tmp))
                fixture.env=dict(clean_env)
                try:
                    env={**clean_env,**fixture.setup('camp'),'JAVA_TOOL_OPTIONS':'-XX:+UseSerialGC -XX:ActiveProcessorCount=2'}
                    command(['mvn','-B','-ntp','-f','backend/pom.xml','verify'],'patched-full-tests',env=env,timeout=3600)
                    import xml.etree.ElementTree as ET
                    totals={k:0 for k in ['tests','errors','failures','skipped']}
                    for p in (BASE/'backend/target/surefire-reports').glob('TEST-*.xml'):
                        r=ET.parse(p).getroot()
                        for k in totals:totals[k]+=int(r.attrib.get(k,0))
                    T.require(totals['tests']>=198 and not any(totals[k] for k in ['errors','failures','skipped']),'All original + four patch tests required')
                    (out/'patch-tests.json').write_text(json.dumps(totals))
                finally:
                    fixture.cleanup()
        else:
            # CI for this exact source must already be green (verified by caller).
            command(['mvn','-B','-ntp','-f','pom.xml' if repo=='shop_bak_ap' else 'backend/pom.xml','-DskipTests','package'],'package')
    jar=None
    if repo!='Shop_back':
        candidates=[p for p in (BASE/('target' if repo=='shop_bak_ap' else 'backend/target')).glob('*.jar') if not p.name.endswith('.original')]
        T.require(len(candidates)==1 and not candidates[0].is_symlink(),'One packaged JAR required');jar=candidates[0]
    tags={}; recipe_sha=recipe_digest()
    for service in T.SERVICES[repo]:
        if project=='encore' and service in ['api-b','worker']:
            tags[service]=tags['api-a'];continue
        kind='gateway' if service=='gateway' else 'app'
        ctx=out/('context-'+service);ctx.mkdir(mode=0o700)
        if project in ['portfolio','encore']:
            dockerfile=(BASE/'infra'/('gateway.Dockerfile' if kind=='gateway' else 'app.Dockerfile')).read_text()
            if kind=='app':
                target=ctx/'backend/target'/jar.name;target.parent.mkdir(parents=True);shutil.copyfile(jar,target)
            else:
                nginx=ctx/'infra/nginx.conf';nginx.parent.mkdir();nginx.write_bytes((BASE/'infra/nginx.conf').read_bytes())
                shutil.copytree(BASE/('dist' if project=='portfolio' else 'frontend/dist'),ctx/('dist' if project=='portfolio' else 'frontend/dist'))
        else:
            dockerfile=recipe(project,service,images)
            if kind=='gateway':
                shutil.copytree(BASE/('dist' if repo=='Shop_back' else 'frontend/dist'),ctx/'dist')
                (ctx/'nginx.conf').write_text(gateway(project))
            elif project=='atlas':
                # Public SDK from pinned official setup-java action, no credentials/models.
                sdk=out/'atlas-public-jre'
                command([str(Path(os.environ['JAVA_HOME'])/'bin/jlink'),'--add-modules','ALL-MODULE-PATH','--strip-debug','--no-header-files','--no-man-pages','--output',str(sdk)],'atlas-jre')
                for path in sdk.rglob('*'):
                    T.require(not path.is_symlink() or path.resolve().is_relative_to(sdk.resolve())
                        or path.resolve().is_relative_to(Path(os.environ['JAVA_HOME']).resolve()),'Only public SDK internal links may be flattened')
                shutil.copytree(sdk,ctx/'java',symlinks=False)
                for name in ['scripts/local-deploy.py','scripts/local-model-runtime.py','config/local-rag-artifacts.json']:
                    dest=ctx/name;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(BASE/name,dest)
                config=json.loads((BASE/'config/local-deployment.example.json').read_text())
                config.update(instance='homelab',accountsFile='work/accounts.json',store='data/store.json',collection='atlas_homelab_v4')
                config['models'].update(threads=2,workDirectory='work/runtime')
                (ctx/'config/homelab.json').write_text(json.dumps(config))
                dest=ctx/'backend/target'/jar.name;dest.parent.mkdir(parents=True);shutil.copyfile(jar,dest)
            else:
                shutil.copyfile(jar,ctx/'app.jar')
        for p in ctx.rglob('*'):
            T.require(not p.is_symlink(),'No symlinks in minimal image context')
        (ctx/'Dockerfile').write_text(dockerfile)
        package='showcase-'+project+'-'+('api' if project=='encore' and service=='api-a' else service)
        tag='ghcr.io/'+REGISTRY+'/'+package+':'+source
        command(['docker','build','--platform','linux/amd64','--build-arg','JAVA_IMAGE='+images['java21']['image'],
            '--build-arg','NGINX_IMAGE='+images['nginx']['image'],'--label','org.opencontainers.image.source=https://github.com/'+OWNER+'/'+repo,
            '--label','org.opencontainers.image.revision='+source,'--label','dev.elpsy.showcase.recipe='+recipe_sha,'-t',tag,str(ctx)],'build-'+service,timeout=1800)
        metadata=json.loads(subprocess.check_output(['docker','image','inspect',tag]))[0]
        T.require(metadata['Config']['User'] in ['10001:10001','101:101'],'Nonroot runtime required')
        if kind=='gateway':
            command(['docker','run','--rm','--network','none','--read-only','--cap-drop','ALL','--tmpfs','/tmp:size=32m',
                '--add-host','api:127.0.0.1','--add-host','backend:127.0.0.1','--add-host','minio:127.0.0.1','--add-host','api-a:127.0.0.1','--add-host','api-b:127.0.0.1',
                '--entrypoint','nginx',tag,'-t'],'nginx-'+service)
        if project=='atlas':
            command(['docker','run','--rm','--network','none','--entrypoint','/opt/java/bin/java',tag,'-version'],'atlas-java')
            command(['docker','run','--rm','--network','none','--read-only','--entrypoint','python3',tag,'-c',
                'import ast,pathlib; [ast.parse(p.read_text()) for p in pathlib.Path("/app/scripts").glob("*.py")]'],'atlas-python')
        tags[service]=tag
    return tags


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--publish',action='store_true');parser.add_argument('--preflight',action='store_true')
    args=parser.parse_args();os.umask(0o077)
    config=json.loads((BASE/'.homelab/release-project.json').read_text());repo=config['repository'];project=config['project']
    # Logical repository key (build/record) vs actual GitHub repository name (API/runner identity).
    ghname=config.get('github_repository',repo)
    if ghname!=repo:T.CI_PATH[ghname]=T.CI_PATH[repo]
    T.require(os.environ.get('GITHUB_ACTIONS')=='true' and os.environ.get('RUNNER_ENVIRONMENT')=='github-hosted'
        and os.environ.get('GITHUB_REPOSITORY')==OWNER+'/'+ghname,'Only original hosted runner')
    token=os.environ.get('GH_TOKEN','');T.require(token,'Ephemeral GitHub token required')
    api=T.GitHub(token);source=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    if args.preflight:
        # Branch consists only of new packaging files. Bind actual business tree to accepted main.
        ci_source=config['accepted_main'];ci_run=config['accepted_ci']
        for path in config['source_paths']:
            def obj(ref):return subprocess.check_output(['git','rev-parse',ref+':'+path],text=True).strip()
            T.require(obj(source)==obj(ci_source),'Packaging preflight business source differs from accepted main')
    else:
        ci_source=source
        event=json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
        ci_run=event.get('workflow_run',{}).get('id')
        T.require(not args.preflight and os.environ.get('GITHUB_REF')=='refs/heads/main','Only main publication')
        T.require(api.get('/repos/'+OWNER+'/'+ghname+'/branches/main')['commit']['sha']==source,'Stale publication refused')
    T.run_verified(api,ghname,ci_run,ci_source,T.CI_PATH[repo])
    # Public repository allowed (owner-approved 2026-11-09); publish only for this exact repository.
    T.require(api.get('/repos/'+OWNER+'/'+ghname).get('id')==int(os.environ['GITHUB_REPOSITORY_ID']),'Only this source repository')
    out=BASE/'work/release';out.mkdir(parents=True,mode=0o700,exist_ok=False)
    result={'status':'FAIL','project':project,'repository':repo,'source':source,'registry':'NOT DEPLOYED','host':'NOT DEPLOYED'}
    try:
        tags=build(project,repo,out,source)
        artifacts={}
        for ctx in sorted(out.glob('context-*')):
            for path in sorted(ctx.rglob('*')):
                relative=path.relative_to(ctx)
                if path.is_file() and (path.suffix=='.jar' or 'dist' in relative.parts):
                    artifacts[str(Path(ctx.name)/relative)]=T.sha(path.read_bytes())
        T.validate_artifacts(artifacts)
        result.update(artifact_count=len(artifacts),artifact_map_sha256=T.sha(T.canonical(artifacts)))
        if not args.publish:
            result.update(status='PASS',scope='Real packaging/image preflight only; no GHCR push or host deployment',services=sorted(tags))
            return
        T.require(not args.preflight and os.environ.get('HOMELAB_PUBLISH_ENABLED')=='true','Explicit publication variable required')
        bootstrap=os.environ.get('HOMELAB_BOOTSTRAP_PRIVATE')=='true'
        for tag in set(tags.values()):
            T.private_package(api,tag.split('/')[2].split(':')[0],ghname,bootstrap,defer_to_oci=True)
        registry=out/'docker-auth';registry.mkdir(mode=0o700)
        env={**os.environ,'DOCKER_CONFIG':str(registry)}
        # Token passes only via stdin to login, never argv/log/image/artifact.
        p=subprocess.run(['docker','login','ghcr.io','-u',os.environ['GITHUB_ACTOR'],'--password-stdin'],input=token.encode(),env=env,capture_output=True,timeout=60)
        T.require(p.returncode==0,'Ephemeral registry login failed')
        digests={}
        try:
            for service,tag in tags.items():
                if tag in digests:continue
                p=subprocess.run(['docker','push',tag],env=env,capture_output=True,timeout=900)
                T.require(p.returncode==0,'Private image push failed; output suppressed')
                values=json.loads(subprocess.check_output(['docker','image','inspect',tag],env=env))[0]['RepoDigests']
                prefix=tag.rsplit(':',1)[0]+'@sha256:'
                values=[v for v in values if v.startswith(prefix)]
                T.require(len(values)==1,'One registry digest required');digests[tag]=values[0]
                T.private_package(api,tag.split('/')[2].split(':')[0],ghname,defer_to_oci=True)
                # A private package response is not provenance. Verify digest,
                # actual image labels, nonroot user and platform before delivery.
                metadata=json.loads(subprocess.check_output(['docker','image','inspect',tag],env=env))[0]
                T.verify_local_image(metadata,digests[tag],{'repository':repo,'source':source,'recipe_sha256':recipe_digest()},service)
        finally:
            shutil.rmtree(registry) # only this generated ephemeral auth directory
        contract={p:subprocess.check_output(['git','rev-parse',source+':'+p],text=True).strip() for p in T.FENCES[repo]}
        release={'schema':3,'project':project,'repository':repo,'source':source,'ci_run':ci_run,'publisher_run':int(os.environ['GITHUB_RUN_ID']),
            'artifacts':artifacts,
            'contract':contract,'images':{s:digests[tag] for s,tag in tags.items()},'recipe_sha256':recipe_digest()}
        delivery=out/'delivery';delivery.mkdir(mode=0o700);(delivery/'release.json').write_bytes(T.canonical(release))
        result.update(status='PASS',registry='PASS',images=release['images'])
    except Exception as error:
        result['error_type']=type(error).__name__;raise
    finally:
        (out/'summary.json').write_text(json.dumps(result,indent=2)+'\n')


if __name__=='__main__':main()
