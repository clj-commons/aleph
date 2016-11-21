(ns aleph.tcp-ssl-test
  (:use
    [clojure test])
  (:require [aleph.tcp-test :refer [with-server]]
            [aleph.tcp :as tcp]
            [manifold.stream :as s]
            [byte-streams :as bs])
  (:import [java.security KeyFactory PrivateKey]
           [java.util Base64]
           [java.security.cert CertificateFactory X509Certificate]
           [java.io ByteArrayInputStream]
           [java.security.spec RSAPrivateCrtKeySpec]
           [io.netty.handler.ssl SslContextBuilder ClientAuth]))

(defn ^PrivateKey gen-key
  [k]
  (let [spec (RSAPrivateCrtKeySpec. (:modulus k) (:publicExponent k) (:privateExponent k)
                                    (:prime1 k) (:prime2 k) (:exponent1 k) (:exponent2 k)
                                    (:coefficient k))
        gen (KeyFactory/getInstance "RSA")]
    (.generatePrivate gen spec)))

(defn ^X509Certificate gen-cert
  [^String pemstr]
  (.generateCertificate (CertificateFactory/getInstance "X.509")
                        (ByteArrayInputStream. (.decode (Base64/getDecoder) pemstr))))

(def ca-cert
  (gen-cert "MIIDyjCCArKgAwIBAgIJAPj8IfB83MXVMA0GCSqGSIb3DQEBCwUAMHIxCzAJBgNVBAYTAlVTMRMwEQYDVQQIDApDYWxpZm9ybmlhMRIwEAYDVQQHDAlDdXBlcnRpbm8xGDAWBgNVBAoMD0JGUCBDb3Jwb3JhdGlvbjEOMAwGA1UECwwFQWxlcGgxEDAOBgNVBAMMB1Jvb3QgQ0EwHhcNMTYxMTIxMjEzMTIzWhcNMzcwMjI0MjEzMTIzWjByMQswCQYDVQQGEwJVUzETMBEGA1UECAwKQ2FsaWZvcm5pYTESMBAGA1UEBwwJQ3VwZXJ0aW5vMRgwFgYDVQQKDA9CRlAgQ29ycG9yYXRpb24xDjAMBgNVBAsMBUFsZXBoMRAwDgYDVQQDDAdSb290IENBMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA1kKISz7cCJIU7pk+JBOH8+6UfvtR7BS1hTkWMw+IsTa9O1EJJqEtiJZTF267nLog+jfUr8AHSTR+qtKkbs77XrOMlaa6Zyq3Z2d/p8R3oUdurg6T3JECGwilYDsEMLNLXnqnUdkeWQJ7ea7UzgJ7ACZ61I4+Dv9xJQ+5BGMRkH+SUTDQ/um8UmrPxbDDljR7TbTY7WtAPbxbALrEKA5EfNS1vdcYCfguN0BUcHaHEiBDAIU7IXZigdPBnSTDHhqBYHjmgQZ9U/ojrvmjG9lsG6X5WGj5H1SZCmpWbp+WiNEgHckzhRkCKU5V53mpqcrFQ5WJjAHGQrBF7CD1IUj6VwIDAQABo2MwYTAdBgNVHQ4EFgQUHZFU7TsvVmLorae0LntY0bhIRwIwHwYDVR0jBBgwFoAUHZFU7TsvVmLorae0LntY0bhIRwIwDwYDVR0TAQH/BAUwAwEB/zAOBgNVHQ8BAf8EBAMCAYYwDQYJKoZIhvcNAQELBQADggEBACfuSp0gy8QI1BP6bAueT6/t7Nz2Yg2kwbIXac5sanLc9MjhjG/EjLrkwhCpEVEfFrKDBl/s0wdYoHcVTDlev4H3QOM4WeciaSUsEytihhey72f89ZyvQ+FGbif2BXNk4kPN0eo3t5TXS8Fw/iBi371KZo4jTpdsB0Y3fwKtXw8ieUAlaF86yGHA9bMF7eGXorpShEJ8JRWWy2pV9WtkYw+tBWj7PtXQAIUx4t+J3+B9pSUyHxxArKmZUKa3GpJzBAKXTLHddtadJLqptjZ6pq7OSiihAs3fxVF+TGDJyPyk8K48y9G2MinrYXVzKHeQWqPTrO0jz1F4FL9LiD+HwLc="))

(def ca-key
  (gen-key {:modulus
            (BigInteger. "00d642884b3edc089214ee993e241387f3ee947efb51ec14b5853916330f88b136bd3b510926a12d889653176ebb9cba20fa37d4afc00749347eaad2a46ecefb5eb38c95a6ba672ab767677fa7c477a1476eae0e93dc91021b08a5603b0430b34b5e7aa751d91e59027b79aed4ce027b00267ad48e3e0eff71250fb9046311907f925130d0fee9bc526acfc5b0c396347b4db4d8ed6b403dbc5b00bac4280e447cd4b5bdd71809f82e37405470768712204300853b21766281d3c19d24c31e1a816078e681067d53fa23aef9a31bd96c1ba5f95868f91f54990a6a566e9f9688d1201dc933851902294e55e779a9a9cac54395898c01c642b045ec20f52148fa57" 16)
            :publicExponent
            (biginteger 65537)
            :privateExponent
            (BigInteger. "00cabb7b710f526d7da3f6bbe35389737b7944e2fdf45f189d452865fbfc77cf8ec6f0e8556b4ad8e5c3af6d9da641bed053521e9a096e1b736363491ab38a7fc8f4c55218c789b49e83662820db4282c52f51dc934601a36766ffec150b7af53b9c731bfcd31ee703f5478708eec0e417995161ec118669676ab4fcd0ccd8a2ca871b9bac27c3f101308388663fe3b0ed96b07147171fab3e424ea1975e5f08f4b05f7ead2f64641c1dc9337e8fdaf973d05a8c66336e241ff9912403b08342193e2828cb1345fb272c34830e9065a83b048eb4cefacac3d46ad86a5f40a07c2f40f73fb2306e11e45c391abbe73a732239873c0d4f009e0023b5e28c2a25f911" 16)
            :prime1
            (BigInteger. "00f6cb9ce30e19978bd5439fbd62fd5fe8978d39a903daf50013bb0371ae1c582c69dccf680330d72f91109137e911cb314330e6654ddb7253dac624f044b388feacd78aa1c7f358b1886661ca9aa1ee84cb9880a5196c2385ea9f2158f6bd4269f04e0747c3f8e8d0d5c546683d0f908bc2aaa8ac93be1c06e10cd60eaa43b83f" 16)
            :prime2
            (BigInteger. "00de4045da15fbdf1e4de4c98e5d6a10d41f351e20ae18014550a6fbbfada45d06af40a79b225dafbfcdf2f4a884d4f627f1135bce1a6dce2a818ef14179a2cc25985730a6e37b18e85caeeec010374d5e31687a8a899af6da3ffca8cc4fe39ad3f01d2b2c9af768be4fbf1d2f74218a0ae253b3a9594ede68c11a3c41c8bf77e9" 16)
            :exponent1
            (BigInteger. "00a89b76f5d09e3f60f3349e1f9f4f8784ba756b9d42db06632517b144ab35063061aacfb039edd635d31fc476b42ec9e940045a837f6b9b721a9720895e066263cbe5fdfa854685a3d4924de1433fba5ad355bf1e0c7e4acea4fa4ea81efa32337a4f74bddcef62efb9fc6b1bb00bc02f1bb1c8470f30e4a8f67bf48a545cabb5" 16)
            :exponent2
            (BigInteger. "00c1a8dbb506c6ec4a29b19bf7936a62b39365e394b25e746d03b41d558e66d43088f11b9ad03d36713971e4c21accbe995b35751f8863f9eb8bed1447eb4771ffa8590129caba6e9fba732bd2ebce647a192f62e7e8b3c139b7dbdef1f902e8dc9833b27531ab37f7ece128fb3a84271708d3ca8f5c249f24446f29e660988651" 16)
            :coefficient
            (BigInteger. "00c2dafa9a8b4aa4ea4e3ad2c43b08f7b2a1aa00b6066a7f425dbfb0090c1257067da6b5f8c340dc026a8cd0179258975cbcb5d6f765af21fc5d1815cc349852e3181add22b673917ba51aa9acb9f52992c50360e9f3365275967c4bcd4eecde59da961d9fb8535aed9edbc99049d250b3e77ed9e49b0a36deb1165720ab7f9fdf" 16)}))

(def server-cert
  (gen-cert "MIIErDCCA5SgAwIBAgICEAAwDQYJKoZIhvcNAQELBQAwcjELMAkGA1UEBhMCVVMxEzARBgNVBAgMCkNhbGlmb3JuaWExEjAQBgNVBAcMCUN1cGVydGlubzEYMBYGA1UECgwPQkZQIENvcnBvcmF0aW9uMQ4wDAYDVQQLDAVBbGVwaDEQMA4GA1UEAwwHUm9vdCBDQTAeFw0xNjExMjEyMTM1MTBaFw0zNjAxMjEyMTM1MTBaMHoxCzAJBgNVBAYTAlVTMRMwEQYDVQQIDApDYWxpZm9ybmlhMRIwEAYDVQQHDAlDdXBlcnRpbm8xGDAWBgNVBAoMD0JGUCBDb3Jwb3JhdGlvbjEUMBIGA1UECwwLU2VydmVyIENlcnQxEjAQBgNVBAMMCWxvY2FsaG9zdDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAMgeJ3q5REYkKZ/WKqyNMfUXpPqv89gCzdoltaz4EUb7sLfXeDKNRrOlaxL8MKTPW7Ix8TbCFV+6+U4hhsXwc5Pzln9618LeBz8+sd6ASiyUbTECpZPU/Y2ZV8FHGcaRb4YSPlnQlcZqE988YMHUDO87GBOYNoCMD0TUSmHNczGF0Bhks634L9CIRvgncYijMw68TVzPfj2o7PVLLPvjLp/F+dbYTxmxYTcpkdwXXEC6nmcqmapHNE0qy+/euOTiQ6le2CAJZXlItMqRjHsgUssO/hBmHpHozHmtTUKosbBillQz2BEm5YV7j/lo4U8d6U3CX6db7uLUa2TyKPxPM8kCAwEAAaOCAUIwggE+MAkGA1UdEwQCMAAwEQYJYIZIAYb4QgEBBAQDAgZAMDMGCWCGSAGG+EIBDQQmFiRPcGVuU1NMIEdlbmVyYXRlZCBTZXJ2ZXIgQ2VydGlmaWNhdGUwHQYDVR0OBBYEFANsPsmUzkmGM7CAedjv0X15DqyRMIGkBgNVHSMEgZwwgZmAFB2RVO07L1Zi6K2ntC57WNG4SEcCoXakdDByMQswCQYDVQQGEwJVUzETMBEGA1UECAwKQ2FsaWZvcm5pYTESMBAGA1UEBwwJQ3VwZXJ0aW5vMRgwFgYDVQQKDA9CRlAgQ29ycG9yYXRpb24xDjAMBgNVBAsMBUFsZXBoMRAwDgYDVQQDDAdSb290IENBggkA+Pwh8HzcxdUwDgYDVR0PAQH/BAQDAgWgMBMGA1UdJQQMMAoGCCsGAQUFBwMBMA0GCSqGSIb3DQEBCwUAA4IBAQAxLyQkzsmT658+rOAaTWL8+jhxIFAQ947J9pE4ot7F1Dldc8ODSD/lIVTqwa4X7NoAAa6P/ydYDi5ZYJvkt/csQOxtRDdoA7dwxn3/U+H8Vv1A27qag53FhUDLpDNlCk53lfNXZxRBIOhZs0xG2/N9rhA1syZByBDbzIyDw8daQkyH3M8Cr3rDO/iC709fuZlCJtuHJgo4VFqrDBvySAAKEFg2VllqtjOr0asMXAUJhPTZiUZLXd+C31SaRs5dulC4ND333bRFdbnuV0Y0YoFaBYOrICMTKRpP5SNLWKqbFXIE4UrPfELWM3WVwkkbOwvXdGJnQpknoXdcr/F7A2au"))

(def server-key
  (gen-key {:modulus
            (BigInteger. "00c81e277ab9444624299fd62aac8d31f517a4faaff3d802cdda25b5acf81146fbb0b7d778328d46b3a56b12fc30a4cf5bb231f136c2155fbaf94e2186c5f07393f3967f7ad7c2de073f3eb1de804a2c946d3102a593d4fd8d9957c14719c6916f86123e59d095c66a13df3c60c1d40cef3b18139836808c0f44d44a61cd733185d01864b3adf82fd08846f8277188a3330ebc4d5ccf7e3da8ecf54b2cfbe32e9fc5f9d6d84f19b161372991dc175c40ba9e672a99aa47344d2acbefdeb8e4e243a95ed82009657948b4ca918c7b2052cb0efe10661e91e8cc79ad4d42a8b1b062965433d81126e5857b8ff968e14f1de94dc25fa75beee2d46b64f228fc4f33c9" 16)
            :publicExponent (biginteger 65537)
            :privateExponent
            (BigInteger. "00a2028f91b2658ca7802f4f9292c9687574e3f2b3fa2499f88fb051c9abb7491140bb452ca13870d1c58ccefcee60af231c3b847e01588e7cef928a5ff2e5bc9c3deb4c5f6647f3ba4840787d9abcf22463d5f6bf97d42a45b5ac2ee46200b90361b669560b21065620aa6cd6247588d730af4845c5720271e6163bf5bbff9349c44ebd9c4f136fc4c208279dae2b46f02e78df6548c3c98731d7ffa73eb5caa1e9e00b314c3f4005faec14646e47738ee9d9c4dde5ccc3989d635154682e5b26636e1511043218e7ba1a43f89e8f727673ebc0981f5b194d0a104b20f5b9eeafca028d3f9cc8ec0a06829e451047e0dc1ef1a9947b07a8aa18de3ea46a699811" 16)
            :prime1
            (BigInteger. "00e92258fc20f0526b7127a04efdc8e20509c32339fa304f2e11fce0a31236ed6595e9caf0d7c0c67a63ad4ca5fcd452381e3c22c5e013f8383379c4cc04c454d6a8d232d7c7ca2a5cacea1e460a6df3863467f6e173617e8478a7b33bd572cfc7e22af2ff8ad1da8206554369311ef58198d6654469041e20b6f12e9f0b4f98ed" 16)
            :prime2
            (BigInteger. "00dbbed07c2d0c59166f23f79a1fb3dc479215c0dbd0330077b3ecf09e94d1fc113269a43f3ecbb2bcd0aad6f8dc8b76e0810fef4b35cf148c816ae84aa4675429f7337b6911e092177722ef00d01b21e6785e1e17caf72b0f0263fcc4e0b6740d855a2883357c9bdeb1901721e3d70b532872a2eec6419607eaf919a83556f6cd" 16)
            :exponent1
            (BigInteger. "00954562c87ca6a37f1bc28d8846429b45328cf93f240c4e86670a0d231c8482d82a76b9742010d48484d1ef63d0507a1c686f84ac41df476d64b830e398a1c4d874dbb1a62bcc2bf78ed7906eb43ad65435b5e383530737a4c6444a24a37491c99fec740e5eea230861d9b201e66bb6323693ffdf1f867d6be0f6e82cf9670bb9" 16)
            :exponent2
            (BigInteger. "623e290f9b444e000da852e6810df489baf226cb1f85edcb969173f2322ebf372106c1fbd4a35541bd38e3eb570eb31324206fa77c631c98c4b37b2f03d97e7354a59ba319ef00e1a4cde574c3959dce603a13d22757e1d450094bd4e97228e8729a204aa8fb10e4bb15e481ae4f522cd78488fb9f7f6b0817314f1b38ddac71" 16)
            :coefficient
            (BigInteger. "1784f8c57ea68804d836a6259f93800858b9e3d5f570ab2c682006efd05a2893405317f5aa543b31db8a3fc91362d9fafc91300a3d818f6e71423fe76486fd04a9c064cfb67f25cb5ddc507d060605cc05b1641648d26f09fe0e71ce48a8fab9698ed85b003982d8dbdd09f310ca99fca5ad58eaa61fac179bc2d34dd128ee50" 16)
        }))

(def client-cert
  (gen-cert "MIIEJDCCAwygAwIBAgICEAEwDQYJKoZIhvcNAQELBQAwcjELMAkGA1UEBhMCVVMxEzARBgNVBAgMCkNhbGlmb3JuaWExEjAQBgNVBAcMCUN1cGVydGlubzEYMBYGA1UECgwPQkZQIENvcnBvcmF0aW9uMQ4wDAYDVQQLDAVBbGVwaDEQMA4GA1UEAwwHUm9vdCBDQTAeFw0xNjExMjEyMTQyMzFaFw0zNjAxMjEyMTQyMzFaMHoxCzAJBgNVBAYTAlVTMRMwEQYDVQQIDApDYWxpZm9ybmlhMRIwEAYDVQQHDAlDdXBlcnRpbm8xGDAWBgNVBAoMD0JGUCBDb3Jwb3JhdGlvbjEUMBIGA1UECwwLQ2xpZW50IENlcnQxEjAQBgNVBAMMCWxvY2FsaG9zdDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAKkfLKv6OOBeThZ/qGNlFubAMDMrtbxIL74gVa526RCO2TdGbVc9iqUxCTBbj5sDxoZwdn+jYi0YbRVd5QF6TNo4W8LZkwb7tckDL0ag2o8PCOge8aAEk1xU5HA1Pb4oyhRrnl+22+OdY0Jputn7tMnTP63J6uFc5HXjKsgA+meqMGnzdYIWFapNhPjOq6CuhGikh2O+Rxgu9wXwpwCDbaP9Hl8wCODZhE1MoDXXi1M7gJ2SAcGEzc4vZiPy3Wm0UIMLEblqswHOi1w0X3obdrD8OKTTpauvXoHv5cqdLA7bwLaK2XEpApZfH21XAoa1cpRd4TnwHz7aNgzVINSYjdcCAwEAAaOBuzCBuDAJBgNVHRMEAjAAMBEGCWCGSAGG+EIBAQQEAwIHgDAzBglghkgBhvhCAQ0EJhYkT3BlblNTTCBHZW5lcmF0ZWQgQ2xpZW50IENlcnRpZmljYXRlMB0GA1UdDgQWBBTVEjypn0lUYwu1jSMkWNA4ipfFpDAfBgNVHSMEGDAWgBQdkVTtOy9WYuitp7Que1jRuEhHAjAOBgNVHQ8BAf8EBAMCBeAwEwYDVR0lBAwwCgYIKwYBBQUHAwIwDQYJKoZIhvcNAQELBQADggEBALCvifJ1ROcOuDVnQezjcVcFFhEccBVdi1b022fII9u1Lyp1QtNaXNC8o9vVa//VwInGvlhGwrWUJiey4QxIfhoHlEuWnZ1OIfxVyA3GqWpu0G1zwuqG8hG2kDfV3m4h7QBl9pSNHJIvxtp75j7qj0r3EXTUFAX9j80Fu9kfWJXUmumQOvz4gkZ/4GHz29iS3nah9/Kl4Mswb03mLClxNhLYwhVaFtPaxWcsFpBXJmJPiXwlGuiI169qmn9OFrw5kgQfoQoU97le39TzsLFXHUgmQQyzuraHadbQOY24uPskDbiRMZMPSXYhFt05jEcgVHp50YFkk93rmA4pBQ4XoD0="))

(def client-key
  (gen-key {:modulus
            (BigInteger. "00a91f2cabfa38e05e4e167fa8636516e6c030332bb5bc482fbe2055ae76e9108ed937466d573d8aa53109305b8f9b03c68670767fa3622d186d155de5017a4cda385bc2d99306fbb5c9032f46a0da8f0f08e81ef1a004935c54e470353dbe28ca146b9e5fb6dbe39d634269bad9fbb4c9d33fadc9eae15ce475e32ac800fa67aa3069f375821615aa4d84f8ceaba0ae8468a48763be47182ef705f0a700836da3fd1e5f3008e0d9844d4ca035d78b533b809d9201c184cdce2f6623f2dd69b450830b11b96ab301ce8b5c345f7a1b76b0fc38a4d3a5abaf5e81efe5ca9d2c0edbc0b68ad9712902965f1f6d570286b572945de139f01f3eda360cd520d4988dd7" 16)
            :publicExponent (biginteger 65537)
            :privateExponent
            (BigInteger. "008f1c1b30181842fc6a35ada6af1d1ec8fa7e8efe80d5a77c06f5d3ab43622b29c5f3793c1323b78bdbb7bbdceee32cc9b47fbc367bea1ae0dd85c42382219ca0d82a05f318f042d56c18aecba457edd0ba27b9ef9b09e42cb08571d20bc23b3fed11b83da43de4190da26857be17b9bf436c524257e88633a8f227048197635a0710d9c905b1182d5432ea1c2ac6ca1780b2a9920cf475924903e966e169be8310f185ed6207dfea4b16b4505e2296be395a4320e83c5a22d0ed24450dc39285c9bef2b7f6b8585eb5c4a65d12ec073aac604b244f1be4658d1c81653458dc9f36c10dee78faad1906f30e2c7e03975e9b1c1d28976ec15ff3b1713608132949" 16)
            :prime1
            (BigInteger. "00d913d66616e668078a60e59de8275a9b187c1554e355f7971fa5029bfdc6e837f4cad95d451fff065a1170847a5eb82c93684b61af32646bf75f312b75d83dc7b6b96a824cef9fd39538cc9ba323ab5f6e7dc6e60ebc0566eab41e7546eb5c9e6668b49006e66127f189c34e10aa1e509b16bfd7eb99f072af0d87dfb563785b" 16)
            :prime2
            (BigInteger. "00c7721a2711e183500779265fff3783efd6361bb44d38f8a42537457d0a67d7974ea456c6895ca9f99e28d23cdcf5b4b7487ee0f8ad79b77f7fc21feb73c5415e38c5de4385c61531c2768876763b099c5d8bdb9cd2d6ec66bb7e506608ed379c39122456e89acca8a224e899a953bdddc212fae6299e9380393fe58466cb5935" 16)
            :exponent1
            (BigInteger. "00c49aaee7af7de6624df60c80c3ae40e58f7b72667baf749aaed2685697b5fac413355540a046a6573e63e52057244a7234df94c65842afa9095671d606d95ebaeec767abb3baa36aa20fdb606a94f7b56b01078f7d70d503ad368d0f72b7e01ea669d67f4b8084260520dc7e6ba167eb614b6d5d45c91a79040aa130ffbba359" 16)
            :exponent2
            (BigInteger. "4e5f77096d4c59c663f9666c08a52f125af1ce372eb539777f2c560109cabe7c35a9fc736ddcdcea3b0d3d782f37da38bfa324127450c51bb3ff7b7d9173acf932840690300c239df7158f1045eb731e5fe02a7f58969e34cc6e99774f00b07e922a9fdf0aee7187be97945375a7738fa5c8c1911a3fb72486daa5fd3e4ba015" 16)
            :coefficient
            (BigInteger. "00cb3cb8fc8480768922c77368d31a457b6c8922a72e544ec82bee19cafdf303e2fb236f65edad78a9124d9e6b002d2e6fa592aca443dbb217a6dc124c6d425df7079d91a754cb06c7bd97397dde6a24d13bbef3c8e31ac2578fe46db3d57c05d9f1d04f09bfc465014b6191ce9beea93b6b2d5ea259df9e35768b8f0041d956f8" 16)
            }))

(def server-ssl-context
  (-> (SslContextBuilder/forServer server-key (into-array X509Certificate [server-cert]))
      (.trustManager (into-array X509Certificate [ca-cert]))
      (.clientAuth ClientAuth/OPTIONAL)
      .build))

(def client-ssl-context
  (-> (SslContextBuilder/forClient)
      (.keyManager client-key (into-array X509Certificate [client-cert]))
      (.trustManager (into-array X509Certificate [ca-cert]))
      .build))

(defn ssl-echo-handler
  [s c]
  (s/connect
    ; note we need to inspect the SSL session *after* we start reading
    ; data. Otherwise, the session might not be set up yet.
    (s/map (fn [msg]
             (is (= (.getSubjectDN client-cert)
                    (.getSubjectDN (first (.getPeerCertificates (:ssl-session c))))))
             msg)
           s)
    s))

(deftest test-ssl-echo
  (with-server (tcp/start-server ssl-echo-handler {:port 10001 :ssl-context server-ssl-context})
    (let [c @(tcp/client {:host "localhost" :port 10001 :ssl-context client-ssl-context})]
      (s/put! c "foo")
      (is (= "foo" (bs/to-string @(s/take! c)))))))