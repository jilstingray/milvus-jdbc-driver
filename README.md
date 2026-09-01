# Milvus JDBC Driver

[中文文档](./README.zh.md)

Standard JDBC driver for Milvus. The driver supports `jdbc:milvus://` URLs, translates SQL into `milvus-sdk-java` calls, and can be loaded through `DriverManager`.

The SQL syntax partially references the Milvus SQL syntax from [dbvisitor](https://github.com/zycgit/dbvisitor).

## Build

```bash
mvn clean generate-sources
mvn test
mvn package
mvn clean install
```

Generated parser classes are written to `target/generated-sources/antlr4/io/github/jilstingray/milvus/jdbc/parser`. If an IDE reports imports such as `io.github.jilstingray.milvus.jdbc.parser.MilvusJdbcParser` as unresolved, run `mvn generate-sources` first, then reload the Maven project.

## Connect to Milvus

### Java

```java
Connection conn = DriverManager.getConnection(
    "jdbc:milvus://localhost:19530/default",
    "username",
    "password"
);
```

### URL

`jdbc:milvus://host:19530[/database][?username=...&password=...&token=...&consistencyLevel=STRONG&defaultQueryLimit=16384]`

Supported URL query parameters and connection properties: `user`, `username`, `password`, `token`, `database`, `consistencyLevel`, `defaultQueryLimit`.

- Properties passed through `DriverManager.getConnection(url, properties)` take precedence over values in the URL query string. The selected database can be supplied either through the URL path (`/database`) or through the `database` parameter/property.

- The `database` is optional. Without a selected database, collection statements must call `USE database`, call `Connection.setCatalog`/`setSchema`, or use a database-qualified collection name such as `database.collection`.

- `consistencyLevel` controls the consistency level used by query and search requests. The default is `STRONG`.

- `defaultQueryLimit` controls the `LIMIT` value automatically appended to scalar `SELECT` statements without an explicit `LIMIT`. The default is `16384`, and the configurable range is `1` to `16384`.

### Offline Jar

`mvn clean package` builds two jars:

- `target/milvus-jdbc-driver-0.1.0.jar`: the regular driver jar, which requires dependencies to be provided on the classpath.
- `target/milvus-jdbc-driver-0.1.0-all.jar`: the offline jar, which bundles the Milvus Java SDK, ANTLR runtime, and SLF4J.

For tools that only accept a JDBC driver jar, use the `-all.jar`. Driver class: `io.github.jilstingray.milvus.jdbc.MilvusDriver`.

```xml
<dependency>
  <groupId>io.github.jilstingray</groupId>
  <artifactId>milvus-jdbc-driver</artifactId>
  <version>0.1.0</version>
  <classifier>all</classifier>
</dependency>
```

## Milvus Data Model Mapping

Milvus is not a relational database. This driver exposes a relational-term-compatible view for JDBC tools:

| JDBC / relational term | Milvus data structure | Driver behavior |
| --- | --- | --- |
| Database | Database | A Milvus database is the connection scope. Each Milvus database should use a separate JDBC connection. |
| Schema | No separate namespace | In Milvus, "schema" means collection schema, namely the field definition of a collection. For JDBC metadata compatibility, this driver also returns the current connection's database as the JDBC schema. |
| Table | Collection | In supported statements, SQL table operations are mapped to Milvus collection operations. |
| Column | Field | JDBC column metadata comes from Milvus collection fields. |
| Row / record | Entity | Inserted rows are written to Milvus as entities. |

For Milvus terminology, see the official docs on [databases](https://milvus.io/docs/manage_databases.md), [collections](https://milvus.io/docs/manage-collections.md), and [schema](https://milvus.io/docs/schema.md).

### Are Table and Collection Fully Equivalent?

In this driver, from the data model and execution-layer perspective, a SQL table represents a Milvus collection. This mapping is intentional, but it does not mean the `TABLE` and `COLLECTION` keywords can be used interchangeably in every SQL statement.

Equivalent or near-equivalent forms:

- `SHOW TABLES` and `SHOW COLLECTIONS` both list Milvus collections.
- `DROP TABLE name` and `DROP COLLECTION name` both drop a Milvus collection.
- `CREATE TABLE name (...)` and `CREATE COLLECTION name DIMENSION n` both create a Milvus collection, but `CREATE TABLE` is used to define an explicit field schema, while `CREATE COLLECTION ... DIMENSION` is a more compact shortcut form.
- `ALTER TABLE name RENAME TO new_name` renames a Milvus collection.
- JDBC metadata exposes Milvus collections as tables with table type `COLLECTION`.

Important differences:

- `DESCRIBE name`, `DESCRIBE COLLECTION name`, `DESCRIBE TABLE name`, `DESC TABLE name`, and `SHOW TABLE name` all describe a Milvus collection.
- `SELECT`, `INSERT`, `UPSERT`, `COUNT`, `FLUSH`, and `UPDATE` use object names directly, for example `SELECT * FROM books`; these statements do not use the `TABLE` or `COLLECTION` keyword after `FROM`.
- A two-part reference such as `database.collection` means `Milvus database + collection`, not `schema.table`. The database prefix must match the database bound to the current JDBC connection.
- Three-part relational names such as `database.schema.table` are not supported because Milvus has no independent relational schema namespace.

## SQL Support

Command categories:

- Database: `CREATE/ALTER/DROP DATABASE`, `SHOW DATABASES`, `USE database`
- Collections/tables: `CREATE TABLE`, `CREATE COLLECTION ... DIMENSION`, `DROP TABLE`, `SHOW TABLES`, `SHOW CREATE TABLE`, `ALTER TABLE ... RENAME TO ...`
- Partitions: `CREATE/DROP PARTITION`, `SHOW PARTITION(S)`
- Aliases: `CREATE/ALTER/DROP ALIAS`
- Indexes: `CREATE/DROP INDEX`, `SHOW INDEX(ES)`
- RBAC: `CREATE/DROP USER`, `CREATE/DROP ROLE`, `GRANT/REVOKE ROLE`, `GRANT/REVOKE privilege`
- Data: `INSERT`, `UPSERT`, `UPDATE`, `DELETE`, `COUNT`, scalar `SELECT`, vector search `SELECT ... ORDER BY vector <-> [...]`
- Memory/control: `LOAD TABLE`, `RELEASE TABLE`, `FLUSH`

Examples:

- `SHOW COLLECTIONS`
- `USE default`
- `DESCRIBE COLLECTION collection_name`
- `DESCRIBE TABLE collection_name`
- `CREATE COLLECTION collection_name DIMENSION 768`
- `DROP COLLECTION collection_name`
- `INSERT INTO collection_name (id, vector, name) VALUES (1, [0.1, 0.2], 'a')`
- `UPDATE collection_name SET name = 'b' WHERE id = 1 LIMIT 1`
- `DELETE FROM collection_name WHERE id = 1`
- `SELECT 'keep alive'`
- `SELECT * FROM collection_name`
- `SELECT * FROM collection_name WHERE id < 10 LIMIT 5`
- `SELECT t.* FROM vectordb.collection_name t`
- `SELECT id, title FROM collection_name ORDER BY vector <-> [0.1, 0.2] LIMIT 10`

Scalar `SELECT` statements without an explicit `LIMIT` use the connection's `defaultQueryLimit`, because Milvus requires a limit for empty-expression queries. Vector search maps `ORDER BY vector <-> [...]` to an SDK search call; scalar `SELECT` maps to query.

`UPDATE` requires a `WHERE` filter for scalar-query updates, cannot update the primary key field, and supports vector-search updates with `UPDATE ... ORDER BY vector <-> [...] LIMIT ...`. Without an explicit `LIMIT`, scalar-query updates first count matches and refuse to update more than `defaultQueryLimit` rows. Scalar-field updates use Milvus partial upsert. Vector-field updates use a full-row upsert: the driver queries the matched entities, merges the `SET` values into those entities, and upserts the complete rows so vector replacements are persisted reliably.

`IMPORT` / BulkWriter / MinIO file import commands, as well as `SHOW PROGRESS` and `SHOW GRANTS`, are not currently supported. The latter two can be parsed, but return `SQLFeatureNotSupportedException`.
