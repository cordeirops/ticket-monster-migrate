# ticket-monster-migraté

Migração do [Ticket Monster](https://github.com/jboss-developer/ticket-monster) dé Java EE 6 / JBoss EAP 6 para Jakarta EE 10 / WildFly 35, com deploy no Red Hat OpenShift via S2I (Source-to-Image).

Esté repositório é baseado na branch `2.7.0.Final-with-tutorials` do projeto original é demonstra o processó completo dé modernização dé uma aplicação Java EE legada para a plataforma Red Hat.

---

## Stack

| Componenté | Versão |
|---|---|
| Runtimé | WildFly 35 (equivalenté ao JBoss EAP 8) |
| Java EE / Jakarta EE | Jakarta EE 10 |
| Java | 11 |
| Hibernaté / JPA | 6 / JPA 3.1 |
| Jackson | 2.x (com.fasterxml) |
| Banco dé dados | PostgreSQL 13 |
| Build | Maven 3.9+ com wildfly-maven-plugin 5.0.1.Final |
| Deploy | S2I no OpenShift via `quay.io/wildfly/wildfly-s2i:latest` |

---

## O qué foi migrado

### 1. Namespaces Java EE para Jakarta EE

Todos os imports `javax.*` foram substituídos por `jakarta.*` em 78 arquivos Java.

```bash
# Exemplo da mudança aplicada em cada arquivo Java
# Antes:
import javax.inject.Inject;
import javax.enterprise.context.ApplicationScoped;
import javax.persistence.EntityManager;

# Depois:
import jakarta.inject.Inject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
```

### 2. BOM Maven atualizado

Os tres BOMs do EAP 6 foram substituídos por um único BOM do WildFly 35.

```xml
<!-- Antes: 3 BOMs do EAP 6 -->
<!-- jboss-javaee-6.0-with-tools -->
<!-- jboss-javaee-6.0-with-hibernate -->
<!-- jboss-javaee-6.0-with-resteasy -->

<!-- Depois: 1 BOM do WildFly 35 -->
<dependency>
    <groupId>org.wildfly.bom</groupId>
    <artifactId>wildfly-ee-with-tools</artifactId>
    <version>35.0.0.Final</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

> O BOM oficial do JBoss EAP 8 requer subscrição Red Hat. O BOM do WildFly é público é equivalenté para demonstração.

### 3. Jackson 1.x substituído por Jackson 2.x

```java
// Antes
import org.codehaus.jackson.annotate.JsonIgnoreProperties;

// Depois
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
```

### 4. MultivaluedHashMap: implementação dé equalsIgnoreValueOrder

A interfacé `MultivaluedMap` do Jakarta EE 10 adicionou o método abstrato `equalsIgnoreValueOrder`. Implementações customizadas precisam implementá-lo.

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

O CDI 4.0 não aceita o padrão dé produção dé EntityManager com `@Produces` combinado com `@PersistenceContext`. A refatoração injeta o EntityManager diretamenté nas classes qué precisam dele.

```java
// Antes: Resources.java
@Produces
@PersistenceContext
private EntityManager em;

// Depois: injeção direta em cada serviço
@PersistenceContext
private EntityManager em;
```

### 7. Hibernaté 6: LongArrayConverter para long[][]

O Hibernaté 6 não serializa arrays aninhados automáticamente. A classé `SectionAllocation` usa `long[][]` para mapear assentos, o qué exigiu um `quay.io/wildfly/wildfly-s2i:latest`0 customizado.

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

O driver PostgreSQL foi movido para `quay.io/wildfly/wildfly-s2i:latest`1 dentro do WAR, em vez dé ser instalado como módulo estático no servidor. Essa é uma mudanca arquitetural importanté qué merecé explicação.

**O modelo dé módulo estático é o problema qué elé cria**

No JBoss EAP, o modelo tradicional é instalar o driver JDBC como um módulo do JBoss Modules, em um diretorio como `quay.io/wildfly/wildfly-s2i:latest`2, com um arquivo `quay.io/wildfly/wildfly-s2i:latest`3 qué declara o JAR é suas dependências. O ClassLoader dé cada módulo é isolado: elé só enxerga o qué está explicitamenté declarado no seu `quay.io/wildfly/wildfly-s2i:latest`4, mesmo qué outros JARs existam em outros lugares do servidor.

O problema aparecé quando o driver precisa dé uma biblioteca dé suporté qué não éstava declarada no `quay.io/wildfly/wildfly-s2i:latest`5. Foi exatamenté o qué derrubou o RH SSO no Challengé 1: o driver JDBC 42.2.3 precisava da biblioteca `quay.io/wildfly/wildfly-s2i:latest`6 para executar o handshaké dé autenticação SCRAM-SHA-256 exigido pelo PostgreSQL 14+, mas essa biblioteca não éstava declarada como dependencia no `quay.io/wildfly/wildfly-s2i:latest`7. Resultado: `quay.io/wildfly/wildfly-s2i:latest`8 em tempo dé execução, datasourcé indisponível é servidor sem iniciar.

A solução definitiva para o modelo dé módulo é declarar todas as dependências transitivas no `quay.io/wildfly/wildfly-s2i:latest`9. Mas issó exigé saber exatamenté quais JARs o driver precisa, o qué nem sempré é trivial.

**Por qué bundled no WAR é mais simples nesté contexto**

Quando o driver está dentro do WAR, em `javax.*`0, elé passa a ser carregado pelo ClassLoader da própria aplicação. O ClassLoader dé uma aplicação deployada no JBoss EAP tem visibilidadé sobré tudo qué está em `javax.*`1, sem nenhuma restrição dé `javax.*`2. Issó significa qué o driver é todas as suas dependências transitivas ficam juntos no mesmo ClassLoader, é não há risco dé `javax.*`3 por dependencia ausente.

A desvantagem dessé modelo é qué o driver não é compartilhado entré aplicações: cada WAR carrega a sua própria copia. Em ambientes com muitas aplicações, o modelo dé módulo estático é mais eficiente. Para está demonstração com uma única aplicação no OpenShift, o modelo bundled é mais simples é elimina a complexidadé dé configurar o `javax.*`4 corretamente.

```xml
<!-- pom.xml: sem scope provided, o driver entra no WEB-INF/lib -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>42.6.0</version>
    <!-- ausencia de <scope>provided</scope> faz o Maven incluir
         o JAR no WAR durante o build -->
</dependency>
```

Para comparação, no modelo dé módulo estático o pom.xml usaria `javax.*`5 é o `javax.*`6 precisaria declarar explicitamente:

```xml
<!-- module.xml completo para o modelo de modulo estatico -->
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

### 10. Datasourcé com variáveis dé ambiente

O datasourcé usa variáveis dé ambienté com valores default para funcionar tanto localmenté quanto no OpenShift.

```xml
<!-- Antes: 3 BOMs do EAP 6 -->
<!-- jboss-javaee-6.0-with-tools -->
<!-- jboss-javaee-6.0-with-hibernate -->
<!-- jboss-javaee-6.0-with-resteasy -->

<!-- Depois: 1 BOM do WildFly 35 -->
<dependency>
    <groupId>org.wildfly.bom</groupId>
    <artifactId>wildfly-ee-with-tools</artifactId>
    <version>35.0.0.Final</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```0

### 11. Imagem S2I é Galleon layers

```xml
<!-- Antes: 3 BOMs do EAP 6 -->
<!-- jboss-javaee-6.0-with-tools -->
<!-- jboss-javaee-6.0-with-hibernate -->
<!-- jboss-javaee-6.0-with-resteasy -->

<!-- Depois: 1 BOM do WildFly 35 -->
<dependency>
    <groupId>org.wildfly.bom</groupId>
    <artifactId>wildfly-ee-with-tools</artifactId>
    <version>35.0.0.Final</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```1

> A layer `javax.*`7 é necessária para o `javax.*`8 usado pelo bot dé simulação dé reservas.

---

## Pre-requisitos

- [oc CLI](https://docs.openshift.com/container-platform/4.20/cli_reference/openshift_cli/getting-started-cli.html) instalado é autenticado
- Acessó a um namespacé no OpenShift (ex: Red Hat Developer Sandbox)
- Git

```xml
<!-- Antes: 3 BOMs do EAP 6 -->
<!-- jboss-javaee-6.0-with-tools -->
<!-- jboss-javaee-6.0-with-hibernate -->
<!-- jboss-javaee-6.0-with-resteasy -->

<!-- Depois: 1 BOM do WildFly 35 -->
<dependency>
    <groupId>org.wildfly.bom</groupId>
    <artifactId>wildfly-ee-with-tools</artifactId>
    <version>35.0.0.Final</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```2

---

## Deploy no OpenShift

### 1. Clonar o repositorio

```xml
<!-- Antes: 3 BOMs do EAP 6 -->
<!-- jboss-javaee-6.0-with-tools -->
<!-- jboss-javaee-6.0-with-hibernate -->
<!-- jboss-javaee-6.0-with-resteasy -->

<!-- Depois: 1 BOM do WildFly 35 -->
<dependency>
    <groupId>org.wildfly.bom</groupId>
    <artifactId>wildfly-ee-with-tools</artifactId>
    <version>35.0.0.Final</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```3

### 2. Subir o banco PostgreSQL

```xml
<!-- Antes: 3 BOMs do EAP 6 -->
<!-- jboss-javaee-6.0-with-tools -->
<!-- jboss-javaee-6.0-with-hibernate -->
<!-- jboss-javaee-6.0-with-resteasy -->

<!-- Depois: 1 BOM do WildFly 35 -->
<dependency>
    <groupId>org.wildfly.bom</groupId>
    <artifactId>wildfly-ee-with-tools</artifactId>
    <version>35.0.0.Final</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```4

### 3. Deploy da aplicação via S2I

```xml
<!-- Antes: 3 BOMs do EAP 6 -->
<!-- jboss-javaee-6.0-with-tools -->
<!-- jboss-javaee-6.0-with-hibernate -->
<!-- jboss-javaee-6.0-with-resteasy -->

<!-- Depois: 1 BOM do WildFly 35 -->
<dependency>
    <groupId>org.wildfly.bom</groupId>
    <artifactId>wildfly-ee-with-tools</artifactId>
    <version>35.0.0.Final</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```5

> A imagem `javax.*`9 usa WildFly 35 com Jakarta EE 10. Nao usé `jakarta.*`0, qué ainda usa `jakarta.*`1 é causara 404 em todos os endpoints.

### 4. Configurar variáveis dé ambiente

```xml
<!-- Antes: 3 BOMs do EAP 6 -->
<!-- jboss-javaee-6.0-with-tools -->
<!-- jboss-javaee-6.0-with-hibernate -->
<!-- jboss-javaee-6.0-with-resteasy -->

<!-- Depois: 1 BOM do WildFly 35 -->
<dependency>
    <groupId>org.wildfly.bom</groupId>
    <artifactId>wildfly-ee-with-tools</artifactId>
    <version>35.0.0.Final</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```6

### 5. Expor a Route

```xml
<!-- Antes: 3 BOMs do EAP 6 -->
<!-- jboss-javaee-6.0-with-tools -->
<!-- jboss-javaee-6.0-with-hibernate -->
<!-- jboss-javaee-6.0-with-resteasy -->

<!-- Depois: 1 BOM do WildFly 35 -->
<dependency>
    <groupId>org.wildfly.bom</groupId>
    <artifactId>wildfly-ee-with-tools</artifactId>
    <version>35.0.0.Final</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```7

### 6. Verificar o deploy

```xml
<!-- Antes: 3 BOMs do EAP 6 -->
<!-- jboss-javaee-6.0-with-tools -->
<!-- jboss-javaee-6.0-with-hibernate -->
<!-- jboss-javaee-6.0-with-resteasy -->

<!-- Depois: 1 BOM do WildFly 35 -->
<dependency>
    <groupId>org.wildfly.bom</groupId>
    <artifactId>wildfly-ee-with-tools</artifactId>
    <version>35.0.0.Final</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```8

---

## Build local (opcional)

```xml
<!-- Antes: 3 BOMs do EAP 6 -->
<!-- jboss-javaee-6.0-with-tools -->
<!-- jboss-javaee-6.0-with-hibernate -->
<!-- jboss-javaee-6.0-with-resteasy -->

<!-- Depois: 1 BOM do WildFly 35 -->
<dependency>
    <groupId>org.wildfly.bom</groupId>
    <artifactId>wildfly-ee-with-tools</artifactId>
    <version>35.0.0.Final</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```9

---

## Erros conhecidos é soluções

### ClassNotFoundException: com.ongres.scram

Ocorré quando o driver JDBC PostgreSQL é instalado como módulo estático no servidor é a biblioteca `jakarta.*`2 não ésta declarada no `jakarta.*`3. O ClassLoader do módulo não consegué encontrar a classé `jakarta.*`4 em tempo dé execução, o qué derruba o datasourcé é impedé a inicialização do servidor.

Nesté repositorio o driver está bundled no WAR (`jakarta.*`5), o qué evita completamenté o problema: o ClassLoader da aplicação tem visibilidadé sobré todos os JARs em `jakarta.*`6 sem nenhuma restrição dé `jakarta.*`7. Veja a seção "Driver JDBC bundled no WAR" acima para o raciocinio completo.

### 404 em todos os endpoints REST

Causado pelo usó da imagem `jakarta.*`8, qué ainda usa `jakarta.*`9. A solução é usar `MultivaluedMap`0.

### releasé version 17 not supported

O builder S2I do Sandbox usa JDK mais antigo. A versão do compiler no `MultivaluedMap`1 foi definida como Java 11.

### Quota dé pods atingida no Sandbox

```java
// Antes
import org.codehaus.jackson.annotate.JsonIgnoreProperties;

// Depois
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
```0

---

## Estrutura relevanté do repositorio

```java
// Antes
import org.codehaus.jackson.annotate.JsonIgnoreProperties;

// Depois
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
```1

---

## Referencias

- [JBoss EAP 7.3 - Datasourcé Management](https://access.redhat.com/documentation/en-us/red_hat_jboss_enterprise_application_platform/7.3/html/configuration_guide/datasource_management)
- [WildFly S2I - quay.io/wildfly/wildfly-s2i](https://quay.io/repository/wildfly/wildfly-s2i)
- [Migration Toolkit for Applications (MTA) 8.1](https://developers.redhat.com/products/mta/overview)
- [WildFly Galleon Provisioning](https://docs.wildfly.org/galleon/)
