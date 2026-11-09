#!/usr/bin/env python3
"""Hosted read-only proof for already published images. Never builds/runs/pushes."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.error

P=Path(__file__).resolve().parent;ROOT=P.parent
s=importlib.util.spec_from_file_location('trust',P/'showcase-cd-trust.py');T=importlib.util.module_from_spec(s);s.loader.exec_module(T)


def main():
    T.require(os.environ.get('GITHUB_ACTIONS')=='true' and os.environ.get('RUNNER_ENVIRONMENT')=='github-hosted','Hosted read-only probe only')
    baseline=json.loads((ROOT/'.homelab/package-diagnostic-baseline.json').read_text())
    repo=baseline['repository'];OWNER=os.environ['GITHUB_REPOSITORY_OWNER'];T.OWNER=OWNER;T.require(os.environ['GITHUB_REPOSITORY']==OWNER+'/'+repo,'Own repo only')
    api=T.GitHub(os.environ['GH_TOKEN']);rows=[]
    with tempfile.TemporaryDirectory(prefix='homelab-package-readonly-') as auth:
        env={'PATH':os.environ['PATH'],'DOCKER_CONFIG':auth,'HOME':auth}
        def command(args):
            r=subprocess.run(args,env=env,capture_output=True,timeout=600);T.require(r.returncode==0,'Fixed read-only Docker operation failed; output suppressed');return r.stdout
        r=subprocess.run(['docker','login','ghcr.io','-u',os.environ['GITHUB_ACTOR'],'--password-stdin'],
            input=os.environ['GH_TOKEN'].encode(),env=env,capture_output=True,timeout=60)
        T.require(r.returncode==0,'Read-only registry login failed')
        services=T.SERVICES[repo]
        for service in services:
            if repo=='ticket-platform' and service in ['api-b','worker']:continue
            package='showcase-'+baseline['project']+'-'+('api' if repo=='ticket-platform' and service=='api-a' else service)
            try:api.get('/users/'+OWNER+'/packages/container/'+package)
            except urllib.error.HTTPError as error:
                if error.code==404:
                    rows.append({'package':package,'status':'NOT DEPLOYED','http_status':404});continue
                raise
            status=T.private_package(api,package,repo,defer_to_oci=True)
            tag='ghcr.io/'+OWNER.lower()+'/'+package+':'+baseline['source']
            command(['docker','pull',tag]) # Hosted disposable cache only; no container execution.
            metadata=json.loads(command(['docker','image','inspect',tag]))[0]
            refs=[v for v in metadata.get('RepoDigests',[]) if v.startswith(tag.rsplit(':',1)[0]+'@sha256:')]
            T.require(len(refs)==1,'One actual registry digest required')
            T.verify_local_image(metadata,refs[0],baseline,service)
            rows.append({'package':package,'status':'PASS','private_metadata':status,'http_status':200,
                'digest':refs[0],'source':baseline['source'],'oci_source_revision_recipe_user_platform':'PASS'})
    T.require(any(r['status']=='PASS' for r in rows),'At least one already published image must be independently verified')
    print('HOMELAB_OCI_PROOF='+json.dumps({'repository':repo,'status':'PASS','rows':rows},sort_keys=True))


if __name__=='__main__':main()
