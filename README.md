# wildfly-amq-broker-ssl

This project demonstrates how-to install a WildFly based microservice on OpenShift which connects to a remote Artemis AcriveMQ Broker;

## Manual Setup on OpenShift

### Deploy activemq-artemis-operator

Either use Operator HUB (recommended) or install it manually:

https://github.com/rh-messaging/activemq-artemis-operator/blob/main/docs/getting-started/quick-start.md

```bash
kubectl apply -f https://github.com/arkmq-org/activemq-artemis-operator/releases/latest/download/activemq-artemis-operator.yaml
```

### Using Custom Certificates

#### Generates a key pair into a JKS store (privatekey.pkcs12)

We prepare a `keystore` containing a **KEY** + **self-signed certificate** (**self-signed certificate** = a certificate, containing the **public key** corresponding to the **KEY**, which is signed with the **KEY** itself):
```shell
keytool -genkeypair -noprompt -alias server -keyalg RSA -keysize 2048 -sigalg SHA256withRSA -dname "CN=ex-aao-all-0-svc-rte.amq.svc.cluster.local" -validity 365 -keystore privatekey.pkcs12 -storepass 1234PIPPOBAUDO -storetype PKCS12 -ext 'san=dns:*.amq.svc.cluster.local'
```

List the content in `privatekey.pkcs12`:
```shell
keytool -list -keystore privatekey.pkcs12 -storepass 1234PIPPOBAUDO
```

#### Exports certificate (truststore.pkcs12)

We export the **self-signed certificate** contained in the `keystore`:
```shell
keytool -exportcert -noprompt -rfc -alias server -file hostname.crt -keystore privatekey.pkcs12 -storepass 1234PIPPOBAUDO -storetype PKCS12
```
We make a `truststore` containing the **self-signed certificate**:
```shell
keytool -import -v -trustcacerts -noprompt -alias server -file hostname.crt -keystore truststore.pkcs12 -storetype PKCS12 -storepass 1234PIPPOBAUDO
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
    brokerProperties:
    - addressConfigurations.test-address.routingTypes=MULTICAST
    - addressConfigurations.out-address.routingTypes=MULTICAST
    - addressConfigurations.in-address.routingTypes=MULTICAST
    - addressConfigurations.test-address.queueConfigs.test-queue.routingType=ANYCAST
    - addressConfigurations.test-address.queueConfigs.test-queue.addressName=testQueue
    - addressConfigurations.test-address.queueConfigs.test-queue.queueName=testQueue
    - addressConfigurations.out-address.queueConfigs.out-queue.routingType=ANYCAST
    - addressConfigurations.out-address.queueConfigs.out-queue.addressName=outQueue
    - addressConfigurations.out-address.queueConfigs.out-queue.queueName=outQueue
    - addressConfigurations.in-address.queueConfigs.in-queue.routingType=ANYCAST
    - addressConfigurations.in-address.queueConfigs.in-queue.addressName=inQueue
    - addressConfigurations.in-address.queueConfigs.in-queue.queueName=inQueue
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

Alternatively, configure addresses like:
```shell
cat <<EOF > /tmp/ActiveMQArtemisAddress.yaml
kind: ActiveMQArtemisAddress
apiVersion: broker.amq.io/v1beta1
metadata:
  name: test-queue
spec:
  addressName: testQueue
  queueName: testQueue
  routingType: anycast
status:
  conditions: []
---
kind: ActiveMQArtemisAddress
apiVersion: broker.amq.io/v1beta1
metadata:
  name: out-queue
spec:
  addressName: outQueue
  queueName: outQueue
  routingType: anycast
status:
  conditions: []  
---
kind: ActiveMQArtemisAddress
apiVersion: broker.amq.io/v1beta1
metadata:
  name: in-queue
spec:
  addressName: inQueue
  queueName: inQueue
  routingType: anycast
status:
  conditions: []
EOF

oc apply -f /tmp/ActiveMQArtemisAddress.yaml
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