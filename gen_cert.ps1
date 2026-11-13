$cert = New-SelfSignedCertificate -DnsName 'mytv' -CertStoreLocation 'Cert:\CurrentUser\My' -KeyExportPolicy Exportable
$pwd = ConvertTo-SecureString -String 'android' -Force -AsPlainText
Export-PfxCertificate -Cert $cert -FilePath 'app\mytv.p12' -Password $pwd
Write-Host "Exported mytv.p12 successfully"

