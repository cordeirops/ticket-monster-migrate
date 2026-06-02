# ticket-monster-migrate

Migração do [Ticket Monster](https://github.com/jboss-developer/ticket-monster) de Java EE 6 / JBoss EAP 6 para Jakarta EE 10 / WildFly 35, com deploy no Red Hat OpenShift via S2I (Source-to-Image).

Este repositório é baseado na branch `2.7.0.Final-with-tutorials` do projeto original e demonstra o processo completo de modernização de uma aplicação Java EE legada para a plataforma Red Hat.

---

## Stack

| Componente | Versão |
|---|---|
| Runtime | WildFly 35 (equivalente ao JBoss EAP 8) |
| Java EE / Jakarta EE | Jakarta EE 10 |
| Java | 11 |
| Hibernate / JPA | 6 / JPA 3.1 |
| Jackson | 2.x (com.fasterxml) |
| Banco de dados | PostgreSQL 13 |
| Build | Maven 3.9+ com wildfly-maven-plugin 5.0.1.Final |
| Deploy | S2I no OpenShift via `quay.io/wildfly/wildfly-s2i:latest` |

---

## O que foi migrado

### 1. Namespaces Java EE para Jakarta EE

Todos os imports `javax.*` foram substituídos por `jakarta.*` em 78 arquivos Java.

```java
// Antes
import javax.inject.Inject;
import javax.enterprise.context.ApplicationScoped;
import javax.persistence.EntityManager;

// Depois
import jakarta.inject.Inject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
```

### 2. BOM Maven atualizado

Os três BOMs do EAP 6 foram substituídos por um único BOM do WildFly 35.

```xml
<!-- Antes: 3 BOMs do EAP 6 -->
<!-- jboss-javaee-6.0-with-tools      -->
<!-- jboss-javaee-6.0-with-hibernate  -->
<!-- jboss-javaee-6.0-with-resteasy   -->

<!-- Depois: 1 BOM do WildFly 35 -->
<dependency>
    <groupId>org.wildfly.bom</groupId>
    <artifactId>wildfly-ee-with-tools</artifactId>
    <version>35.0.0.Final</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

> O BOM oficial do JBoss EAP 8 requer subscrição Red Hat. O BOM do WildFly é público e equivalente para demonstração.

### 3. Jackson 1.x substituído por Jackson 2.x

```java
// Antes
import org.codehaus.jackson.annotate.JsonIgnoreProperties;

// Depois
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
```

### 4. MultivaluedHashMap: implementação de equalsIgnoreValueOrder

A interface `MultivaluedMap` do Jakarta EE 10 adicionou o método abstrato `equalsIgnoreValueOrder`. Implementações customizadas precisam implementá-lo.

```java
@Override
public boolean equalsIgnoreValueOrder(MultivaluedMap<K, V> otherMap) {
    if (this == otherMap) return true;
    if (otherMap == null || !keySet().equals(otherMap.keySet())) return false;
    for (Map.Entry<K, List<V>> entry : entrySet()) {
        List<V> other = otherMap.get(entry.getKey());
        if (other == null || entry.getValue().size() != other.size()
            || !entry.getValue().containsAll(other)) return false;
    }
    return true;
}
```

### 5. resteasy-jaxrs substituído por resteasy-core

```xml
<!-- Antes -->
<artifactId>resteasy-jaxrs</artifactId>

<!-- Depois -->
<artifactId>resteasy-core</artifactId>
<version>6.2.10.Final</version>
```

### 6. CDI 4.0: padrão @Produces @PersistenceContext removido

O CDI 4.0 não aceita o padrão de produção de EntityManager com `@Produces` combinado com `@PersistenceContext`. A refatoração injeta o EntityManager diretamente nas classes que precisam dele.

```java
// Antes: Resources.java
@Produces
@PersistenceContext
private EntityManager em;

// Depois: injeção direta em cada serviço
@PersistenceContext
private EntityManager em;
```

### 7. Hibernate 6: LongArrayConverter para long[][]

O Hibernate 6 não serializa arrays aninhados automaticamente. A classe `SectionAllocation` usa `long[][]` para mapear assentos, o que exigiu um `AttributeConverter` customizado.

```java
@Converter
public class LongArrayConverter
    implements AttributeConverter<long[][], String> {

    @Override
    public String convertToDatabaseColumn(long[][] attribute) {
        if (attribute == null) return null;
        // serializa como JSON string
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < attribute.length; i++) {
            sb.append(Arrays.toString(attribute[i]));
            if (i < attribute.length - 1) sb.append(",");
        }
        return sb.append("]").toString();
    }

    @Override
    public long[][] convertToEntityAttribute(String dbData) {
        // desserializa de volta para long[][]
        ...
    }
}
```

### 8. Driver JDBC bundled no WAR

O driver PostgreSQL foi movido para `WEB-INF/lib` dentro do WAR, em vez de ser instalado como módulo estático no servidor. Essa é uma mudança arquitetural importante que merece explicação.

**O modelo de módulo estático e o problema que ele cria**

No JBoss EAP, o modelo tradicional é instalar o driver JDBC como um módulo do JBoss Modules, em um diretório como `$EAP_HOME/modules/org/postgresql/main/`, com um arquivo `module.xml` que declara o JAR e suas dependências. O ClassLoader de cada módulo é isolado: ele só enxerga o que está explicitamente declarado no seu `module.xml`, mesmo que outros JARs existam em outros lugares do servidor.

O problema aparece quando o driver precisa de uma biblioteca de suporte que não estava declarada no `module.xml`. Foi exatamente o que derrubou o RH SSO no Challenge 1: o driver JDBC 42.2.3 precisava da biblioteca `ongres/scram` para executar o handshake de autenticação SCRAM-SHA-256 exigido pelo PostgreSQL 14+, mas essa biblioteca não estava declarada como dependência no `module.xml`. Resultado: `ClassNotFoundException` em tempo de execução, datasource indisponível e servidor sem iniciar.

**Por que bundled no WAR é mais simples neste contexto**

Quando o driver está dentro do WAR, em `WEB-INF/lib`, ele é carregado pelo ClassLoader da própria aplicação. O ClassLoader de uma aplicação deployada no JBoss EAP tem visibilidade sobre tudo que está em `WEB-INF/lib`, sem nenhuma restrição de `module.xml`. Isso significa que o driver e todas as suas dependências transitivas ficam no mesmo classloader, sem risco de `ClassNotFoundException` por dependência ausente.

A desvantagem desse modelo é que o driver não é compartilhado entre aplicações: cada WAR carrega a sua própria cópia. Em ambientes com muitas aplicações, o modelo de módulo estático é mais eficiente. Para esta demonstração com uma única aplicação no OpenShift, o modelo bundled é mais simples e elimina a complexidade de configurar o `module.xml` corretamente.

```xml
<!-- pom.xml: sem scope provided, o driver entra no WEB-INF/lib -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>42.6.0</version>
    <!-- ausência de <scope>provided</scope> faz o Maven incluir
         o JAR no WAR durante o build -->
</dependency>
```

Para comparação, no modelo de módulo estático o `module.xml` precisaria declarar explicitamente:

```xml
<!-- module.xml completo para o modelo de módulo estático -->
<module name="org.postgresql">
    <resources>
        <resource-root path="postgresql-42.6.0.jar"/>
        <resource-root path="ongres-scram-client-2.1.jar"/>
        <resource-root path="ongres-scram-common-2.1.jar"/>
    </resources>
    <dependencies>
        <module name="javax.api"/>
        <module name="javax.transaction.api"/>
    </dependencies>
</module>
```

### 9. persistence.xml atualizado para Jakarta EE 3.0

```xml
<!-- Antes -->
<persistence xmlns="http://java.sun.com/xml/ns/persistence"
             version="2.0">

<!-- Depois -->
<persistence xmlns="https://jakarta.ee/xml/ns/persistence"
             version="3.0">
```

### 10. Datasource com variáveis de ambiente

O datasource usa variáveis de ambiente com valores default para funcionar tanto localmente quanto no OpenShift.

```xml
<datasource jndi-name="java:jboss/datasources/PostgreSQLDS"
            pool-name="PostgreSQLDS" enabled="true">
    <connection-url>
        jdbc:postgresql://${env.DB_HOST:ticketmonster-db}:${env.DB_PORT:5432}/${env.DB_DATABASE:ticketmonster}
    </connection-url>
    <driver>postgresql</driver>
    <security>
        <user-name>${env.DB_USERNAME:ticketmonster}</user-name>
        <password>${env.DB_PASSWORD:ticketmonster}</password>
    </security>
</datasource>
```

### 11. Imagem S2I e Galleon layers

```xml
<!-- pom.xml: wildfly-maven-plugin -->
<plugin>
    <groupId>org.wildfly.plugins</groupId>
    <artifactId>wildfly-maven-plugin</artifactId>
    <version>5.0.1.Final</version>
    <configuration>
        <feature-packs>
            <feature-pack>
                <location>org.wildfly:wildfly-galleon-pack:35.0.0.Final</location>
            </feature-pack>
        </feature-packs>
        <layers>
            <layer>cloud-server</layer>
            <layer>ejb</layer>
        </layers>
    </configuration>
</plugin>
```

> A layer `ejb` é necessária para o `EJB TimerService` usado pelo bot de simulação de reservas.

---

## Pré-requisitos

- [oc CLI](https://docs.openshift.com/container-platform/4.20/cli_reference/openshift_cli/getting-started-cli.html) instalado e autenticado
- Acesso a um namespace no OpenShift (ex: Red Hat Developer Sandbox)
- Git

```bash
# Verificar login no OpenShift
oc whoami
oc project
```

---

## Deploy no OpenShift

### 1. Clonar o repositório

```bash
git clone https://github.com/cordeirops/ticket-monster-migrate.git
cd ticket-monster-migrate
```

### 2. Subir o banco PostgreSQL

```bash
oc new-app postgresql \
  -e POSTGRESQL_USER=ticketmonster \
  -e POSTGRESQL_PASSWORD=ticketmonster \
  -e POSTGRESQL_DATABASE=ticketmonster \
  --name=ticketmonster-db

# Aguardar o banco ficar pronto
oc rollout status deployment/ticketmonster-db
```

### 3. Deploy da aplicação via S2I

```bash
oc new-app quay.io/wildfly/wildfly-s2i:latest~https://github.com/cordeirops/ticket-monster-migrate \
  --name=ticket-monster \
  --context-dir=demo \
  --strategy=source

# Acompanhar o build
oc logs -f bc/ticket-monster
```

> Use sempre `quay.io/wildfly/wildfly-s2i:latest` (WildFly 35, Jakarta EE 10). A imagem `wildfly-centos7` ainda usa `javax.*` e causa 404 em todos os endpoints.

### 4. Configurar variáveis de ambiente

```bash
oc set env deployment/ticket-monster \
  DB_HOST=ticketmonster-db \
  DB_PORT=5432 \
  DB_DATABASE=ticketmonster \
  DB_USERNAME=ticketmonster \
  DB_PASSWORD=ticketmonster
```

### 5. Expor a Route

```bash
oc expose service/ticket-monster

# Obter a URL
oc get route ticket-monster
```

### 6. Verificar o deploy

```bash
# Status do pod
oc get pods -l app=ticket-monster

# Logs do servidor WildFly
oc logs deployment/ticket-monster

# Testar os endpoints REST
curl http://$(oc get route ticket-monster -o jsonpath='{.spec.host}')/rest/events
curl http://$(oc get route ticket-monster -o jsonpath='{.spec.host}')/rest/venues
```

---

## Build local (opcional)

```bash
cd demo
mvn clean package -DskipTests

# O WAR gerado fica em:
# demo/target/ticket-monster.war
```

---

## Erros conhecidos e soluções

### ClassNotFoundException: com.ongres.scram

Ocorre quando o driver JDBC PostgreSQL é instalado como módulo estático no servidor e a biblioteca `ongres/scram` não está declarada no `module.xml`. O ClassLoader do módulo não consegue encontrar a classe `com.ongres.scram.common.stringprep.StringPreparation` em tempo de execução, o que derruba o datasource e impede a inicialização do servidor.

Neste repositório o driver está bundled no WAR (`WEB-INF/lib`), o que evita completamente o problema: o ClassLoader da aplicação tem visibilidade sobre todos os JARs em `WEB-INF/lib` sem nenhuma restrição de `module.xml`. Veja a seção "Driver JDBC bundled no WAR" acima para o raciocínio completo.

### 404 em todos os endpoints REST

Causado pelo uso da imagem `wildfly-centos7`, que ainda usa `javax.*`. A solução é usar `quay.io/wildfly/wildfly-s2i:latest`.

### release version 17 not supported

O builder S2I do Sandbox usa JDK mais antigo. A versão do compiler no `pom.xml` foi definida como Java 11.

### Quota de pods atingida no Sandbox

```bash
# Limpar pods concluídos
oc delete pods --field-selector=status.phase=Succeeded
```

---

## Estrutura relevante do repositório

```
ticket-monster-migrate/
  demo/
    pom.xml                         # BOM WildFly 35, compiler Java 11
    src/main/
      java/                         # 78 arquivos migrados para jakarta.*
      resources/
        META-INF/
          persistence.xml           # namespace Jakarta EE 3.0
      webapp/
        WEB-INF/
          postgresql-ds.xml         # datasource com variáveis de ambiente
          beans.xml                 # CDI 4.0
```

---

## Referências

- [JBoss EAP 7.3 - Datasource Management](https://access.redhat.com/documentation/en-us/red_hat_jboss_enterprise_application_platform/7.3/html/configuration_guide/datasource_management)
- [WildFly S2I - quay.io/wildfly/wildfly-s2i](https://quay.io/repository/wildfly/wildfly-s2i)
- [Migration Toolkit for Applications (MTA) 8.1](https://developers.redhat.com/products/mta/overview)
- [WildFly Galleon Provisioning](https://docs.wildfly.org/galleon/)
 
 
