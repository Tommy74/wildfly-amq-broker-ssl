# WildFly + Artemis AcriveMQ + TLS

This project demonstrates how-to install a WildFly based microservice on OpenShift which connects to a remote Artemis AcriveMQ Broker which is running on OpenShift too in the same namespace;

## Create TLS Private Key and Self-signed Certificate

Creating the TLS Private Key and Self-signed Certificate is necessary in order to encrypt the communication between WildFly
and the Artemis AcriveMQ Broker;

This step is required for both the [Manual Setup on your laptop](#manual-setup-on-your-laptop) and [Manual Setup on OpenShift](#manual-setup-on-openshift) setups;

### Generate Private Key and Self-signed Certificate into a JKS store (privatekey.pkcs12)

We prepare a `keystore` containing a **PRIVATE KEY** + **self-signed certificate** (**self-signed certificate** = a certificate for the **public key** corresponding to the **PRIVATE KEY** which is signed with the **PRIVATE KEY** itself):

```shell
keytool -genkeypair -noprompt -alias server -keyalg RSA -keysize 2048 -sigalg SHA256withRSA -dname "CN=ex-aao-all-0-svc-rte.amq.svc.cluster.local" -validity 365 -keystore privatekey.pkcs12 -storepass 1234PIPPOBAUDO -storetype PKCS12 -ext 'san=dns:*.amq.svc.cluster.local'
```

List the content in `privatekey.pkcs12`:
```shell
$ keytool -list -keystore privatekey.pkcs12 -storepass 1234PIPPOBAUDO
Keystore type: PKCS12
Keystore provider: SUN

Your keystore contains 1 entry

server, Oct 20, 2025, PrivateKeyEntry, 
Certificate fingerprint (SHA-256): 4D:95:6B:83:6C:E5:77:7E:DA:47:5D:C4:34:09:3B:1A:E5:DE:68:23:F0:D3:0B:7E:AC:F3:71:88:5A:A0:9E:D9
```

### Export Self-signed Certificate (truststore.pkcs12)

First, we export the **self-signed certificate** contained in the `keystore`:

```shell
keytool -exportcert -noprompt -rfc -alias server -file hostname.crt -keystore privatekey.pkcs12 -storepass 1234PIPPOBAUDO -storetype PKCS12
```

Then, we make a `truststore` containing the **self-signed certificate** we just exported:

```shell
keytool -import -v -trustcacerts -noprompt -alias server -file hostname.crt -keystore truststore.pkcs12 -storetype PKCS12 -storepass 1234PIPPOBAUDO
```

## Manual Setup on your laptop

This is only for debug purposes in case you want to try the configuration locally before deploying on OpenShift;

### Artemis AcriveMQ Broker

Download [apache-artemis-2.43.0-bin.zip](https://dlcdn.apache.org/activemq/activemq-artemis/2.43.0/apache-artemis-2.43.0-bin.zip);

Unzip it and you'll get a folder named `apache-artemis-2.43.0`;

```bash
cd apache-artemis-2.43.0
ls
bin  lib  LICENSE  licenses  NOTICE  README.html  schema  web
```

Create a broker in a folder outside the `apache-artemis-2.43.0` folder:

```bash
export ARTEMIS_HOME=/path/to/apache-artemis-2.43.0
${ARTEMIS_HOME}/bin/artemis create mybroker \
  --user=admin \
  --password=admin \
  --require-login
```

Now edit the `mybroker/etc/broker.xml` file and enable TLS by adding `sslEnabled=true;keyStorePath=/some-path/privatekey.pkcs12;keyStorePassword=1234PIPPOBAUDO;` to the URL of the `acceptor` named `artemis` (the one listening on port `61616`):

```xml
<acceptor name="artemis">tcp://0.0.0.0:61616?sslEnabled=true;keyStorePath=/some-path/privatekey.pkcs12;keyStorePassword=1234PIPPOBAUDO;tcpSendBufferSize=1048576;tcpReceiveBufferSize=1048576;amqpMinLargeMessageSize=102400;protocols=CORE,AMQP,STOMP,HORNETQ,MQTT,OPENWIRE;useEpoll=true;amqpCredits=1000;amqpLowCredits=300;amqpDuplicateDetection=true;supportAdvisory=false;suppressInternalManagementObjects=false</acceptor>
```

Start the broker:
```bash
./mybroker/bin/artemis run
```

### WildFly

Set the environment variables needed to point WildFly to the Artemis AcriveMQ Broker and the truststore; 
then build and start WildFly;

```shell
export TRUST_STORE_FILENAME=/some-path/truststore.pkcs12
export TRUSTSTORE_PASSWORD=1234PIPPOBAUDO
export ARTEMIS_USER=admin
export ARTEMIS_PASSWORD=admin
export JBOSS_MESSAGING_CONNECTOR_HOST=localhost
export JBOSS_MESSAGING_CONNECTOR_PORT=61616      
mvn clean install && ./target/server/bin/standalone.sh
```

Now hit:

* http://localhost:8080/jms-test?request=send-message
* http://localhost:8080/jms-test?request=consume-message

to check everything works

## Manual Setup on OpenShift

### Deploy activemq-artemis-operator

Either use Operator HUB (recommended) or install it manually:

https://github.com/rh-messaging/activemq-artemis-operator/blob/main/docs/getting-started/quick-start.md

```bash
kubectl apply -f https://github.com/arkmq-org/activemq-artemis-operator/releases/latest/download/activemq-artemis-operator.yaml
```



### Create Secret containing key and self-signed certificate

```shell
cat <<EOF > /tmp/ex-aao-sslacceptor-secret.yaml
apiVersion: v1
kind: Secret
metadata:
  name: ex-aao-sslacceptor-secret
type: Opaque
stringData:
  alias: server
  keyStorePassword: 1234PIPPOBAUDO
  trustStorePassword: 1234PIPPOBAUDO
data:
  broker.ks: $(cat privatekey.pkcs12 | base64 -w 0)
  client.ts: $(cat truststore.pkcs12 | base64 -w 0)
EOF

oc delete secret ex-aao-sslacceptor-secret
oc apply -f /tmp/ex-aao-sslacceptor-secret.yaml
```

NOTE: "stringData" field is a temporary, more convenient field for users. OpenShift automatically encodes the strings in stringData into base64 and moves them to the data field upon creation or update. This means you can work with plain text strings in stringData and OpenShift handles the encoding for you.

### Create Secret containing self-signed certificate

This one is for the WildFly when connecting to ActiveMQ in order to trust the **self-signed certificate**:

Run either:
```shell
oc create secret generic example-certificate-crt --from-file=artemis.crt=hostname.crt
```
or:
```shell
cat <<EOF > /tmp/ex-aao-truststore-secret.yaml
apiVersion: v1
kind: Secret
metadata:
  name: ex-aao-truststore-secret
type: Opaque
stringData:
  alias: server
  trustStorePassword: 1234PIPPOBAUDO
data:
  client.ts: $(cat truststore.pkcs12 | base64 -w 0)
EOF

oc delete secret ex-aao-truststore-secret
oc apply -f /tmp/ex-aao-truststore-secret.yaml
```

### Create the ActiveMQ Server using these keys

```shell
cat <<EOF > /tmp/ActiveMQArtemis.yaml
apiVersion: broker.amq.io/v1beta1
kind: ActiveMQArtemis
metadata:
  name: ex-aao
  namespace: amq
spec:
  properties:
    adminPassword: admin
    adminUser: 1234PIPPOBAUDO
  acceptors:
  - name: sslacceptor
    protocols: all
    port: 61617
    sslEnabled: true
    sslSecret: ex-aao-sslacceptor-secret
    verifyHost: false
    expose: true
  deploymentPlan:
    image: placeholder
    jolokiaAgentEnabled: false
    journalType: nio
    managementRBACEnabled: true
    messageMigration: false
    persistenceEnabled: false
    requireLogin: false
    size: 1
EOF

oc apply -f /tmp/ActiveMQArtemis.yaml
```

### Inspect ActiveMQ Server

In the POD logs you see:
```shell
2025-10-17 07:29:00,269 INFO  [org.apache.activemq.artemis.core.server] AMQ221020: Started EPOLL Acceptor at ex-aao-ss-0.ex-aao-hdls-svc.amq.svc.cluster.local:61617 for protocols [CORE,MQTT,AMQP,HORNETQ,STOMP,OPENWIRE]
```

If you connect to the POD you can see:
```shell
$ cat /etc/ex-aao-sslacceptor-secret-volume/keyStorePassword 
1234PIPPOBAUDO

$ cat /home/jboss/amq-broker/etc/broker.xml | grep sslacceptor
<acceptor name="sslacceptor">tcp://ex-aao-ss-0.ex-aao-hdls-svc.amq.svc.cluster.local:61617?protocols=AMQP,CORE,HORNETQ,MQTT,OPENWIRE,STOMP;sslEnabled=true;keyStorePath=/etc/ex-aao-sslacceptor-secret-volume/broker.ks;keyStorePassword=1234PIPPOBAUDO;trustStorePath=/etc/ex-aao-sslacceptor-secret-volume/client.ts;trustStorePassword=1234PIPPOBAUDO;tcpSendBufferSize=1048576;tcpReceiveBufferSize=1048576;useEpoll=true;amqpCredits=1000;amqpMinCredits=300</acceptor>
```

which confirms our ActiveMQ Server is using TLS for client connections;

The following verifies you can send and receive messages over TLS:
```shell
cd /home/jboss/amq-broker/bin
   
./artemis producer --user admin --password 1234PIPPOBAUDO --url 'tcp://ex-aao-ss-0.ex-aao-hdls-svc.amq.svc.cluster.local:61617?sslEnabled=true&trustStorePath=/etc/ex-aao-sslacceptor-secret-volume/client.ts&trustStorePassword=1234PIPPOBAUDO&verifyHost=false' --message-count 100

./artemis consumer --user admin --password 1234PIPPOBAUDO --url 'tcp://ex-aao-ss-0.ex-aao-hdls-svc.amq.svc.cluster.local:61617?sslEnabled=true&trustStorePath=/etc/ex-aao-sslacceptor-secret-volume/client.ts&trustStorePassword=1234PIPPOBAUDO&verifyHost=false' --message-count 100
```

NOTE: `verifyHost` is needed, otherwise you might have: `javax.net.ssl.SSLHandshakeException: No subject alternative DNS name matching ex-aao-ss-0.ex-aao-hdls-svc.amq.svc.cluster.local found.`

### Inspect injected variables in the same namespace

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: busybox
  labels:
    app: busybox
spec:
  securityContext:
    runAsNonRoot: true
    seccompProfile:
      type: RuntimeDefault
  containers:
    - name: busybox
      image: 'quay.io/openshifttest/busybox'
      command:
        - "/bin/sh"
        - "-c"
        - "while true; do echo 'sleep 60 ...'; sleep 60; done"
      securityContext:
        allowPrivilegeEscalation: false
        capabilities:
          drop:
            - ALL
```


### Deploy WildFly using Helm

```bash
helm uninstall amq-broker-ssl
helm install amq-broker-ssl -f charts/amq-broker-ssl-custom-certificate.yaml wildfly/wildfly
```