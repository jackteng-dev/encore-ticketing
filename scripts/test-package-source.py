#!/usr/bin/env python3
"""GHCR real-response schema/provenance regression; no token/network/Docker."""
import copy
import importlib.util
from pathlib import Path
import unittest

P=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('trust',P/'showcase-cd-trust.py');T=importlib.util.module_from_spec(s);s.loader.exec_module(T)


class API:
    def __init__(self,value):self.value=value
    def get(self,path):return copy.deepcopy(self.value)


class PackageSource(unittest.TestCase):
    def setUp(self):
        self.package='showcase-portfolio-api';self.repo='developer-portfolio'
        self.value={'name':self.package,'visibility':'private','package_type':'container','owner':{'login':'Elpsy-Deng'}}
        self.ref='ghcr.io/elpsy-deng/'+self.package+'@sha256:'+'f'*64
        self.component={'repository':self.repo,'source':'a'*40,'recipe_sha256':'b'*64}
        self.image={'RepoDigests':[self.ref],'Os':'linux','Architecture':'amd64','Config':{'User':'10001:10001','Labels':{
            'org.opencontainers.image.source':'https://github.com/Elpsy-Deng/'+self.repo,
            'org.opencontainers.image.revision':'a'*40,'dev.jackteng.showcase.recipe':'b'*64}}}
    def check(self,value=None,defer=False):return T.private_package(API(value or self.value),self.package,self.repo,defer_to_oci=defer)
    def test_real_ghcr_shape_requires_source_proof_by_default(self):
        with self.assertRaises(T.PackageSourceEvidenceRequired):self.check()
    def test_explicit_oci_path_is_pending_not_automatic_pass(self):
        self.assertEqual(self.check(defer=True),'PRIVATE_SOURCE_PENDING_OCI')
        self.assertTrue(T.verify_local_image(self.image,self.ref,self.component,'api'))
    def test_public_internal_owner_name_type_still_refused(self):
        for change in [{'visibility':'public'},{'visibility':'internal'},{'owner':{'login':'other'}},{'name':'other'},{'package_type':'npm'}]:
            with self.subTest(change=change),self.assertRaises(ValueError):self.check({**self.value,**change},defer=True)
    def test_present_foreign_repository_never_uses_fallback(self):
        with self.assertRaises(ValueError):self.check({**self.value,'repository':{'full_name':'Elpsy-Deng/other'}},defer=True)
    def test_present_correct_repository(self):
        self.assertEqual(self.check({**self.value,'repository':{'full_name':'Elpsy-Deng/'+self.repo}}),'PASS')
    def test_wrong_oci_source_revision_recipe_cannot_prove_source(self):
        for field in ['org.opencontainers.image.source','org.opencontainers.image.revision','dev.jackteng.showcase.recipe']:
            image=copy.deepcopy(self.image);image['Config']['Labels'][field]='wrong'
            with self.subTest(field=field),self.assertRaises(ValueError):T.verify_local_image(image,self.ref,self.component,'api')
    def test_wrong_digest_platform_or_root_cannot_prove_source(self):
        for field,value in [('RepoDigests',[]),('Architecture','arm64'),('Os','windows')]:
            image=copy.deepcopy(self.image);image[field]=value
            with self.subTest(field=field),self.assertRaises(ValueError):T.verify_local_image(image,self.ref,self.component,'api')
        image=copy.deepcopy(self.image);image['Config']['User']='0'
        with self.assertRaises(ValueError):T.verify_local_image(image,self.ref,self.component,'api')


if __name__=='__main__':unittest.main()
