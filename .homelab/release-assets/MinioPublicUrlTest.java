package com.fadaiting.camp.controller;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MinioPublicUrlTest {
 @Test void preservesRawSignatureAndEncodedObject(){assertEquals("/objects/bucket/a%20b.png?X-Amz-Signature=abc%2Fdef&X-Amz-Credential=x",MinioController.browserUrl("http://minio:9000/bucket/a%20b.png?X-Amz-Signature=abc%2Fdef&X-Amz-Credential=x","/objects"));}
 @Test void keepsUpstreamDefault(){assertEquals("http://minio:9000/a?x=y",MinioController.browserUrl("http://minio:9000/a?x=y",""));}
 @Test void rejectsExternalAndTraversalPrefixes(){for(String prefix:new String[]{"https://evil.invalid","//evil.invalid","/../objects","/objects?x=1","/objects/"})assertThrows(IllegalArgumentException.class,()->MinioController.browserUrl("http://minio:9000/a",prefix));}
 @Test void rejectsNonHttpInput(){assertThrows(IllegalArgumentException.class,()->MinioController.browserUrl("file:///etc/passwd","/objects"));}
}
