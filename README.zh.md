# Milvus JDBC Driver

[English README](./README.md)

[SQL syntax manual](docs/SYNTAX.md) | [中文语法手册](docs/SYNTAX.zh.md)

Milvus 的标准 JDBC 驱动。该驱动支持 `jdbc:milvus://` URL，将 SQL 转换为 `milvus-sdk-java` 调用，并可通过 `DriverManager` 加载。

语法层面参考了 [dbvisitor](https://github.com/zycgit/dbvisitor) 的部分 Milvus SQL 语法。

## 构建

```bash
mvn clean generate-sources
mvn test
mvn package
mvn clean install
```

生成的解析器类位于 `target/generated-sources/antlr4/io/github/jilstingray/milvus/jdbc/parser`。如果 IDE 提示 `io.github.jilstingray.milvus.jdbc.parser.MilvusJdbcParser` 等导入无法解析，请先运行 `mvn generate-sources`，然后重新加载 Maven 项目。

## 连接 Milvus

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

支持的 URL 查询参数和连接属性：`user`、`username`、`password`、`token`、`database`、`consistencyLevel`、`defaultQueryLimit`。

- 通过 `DriverManager.getConnection(url, properties)` 传入的属性优先于 URL 查询字符串中的值。当前选中的 database 可以通过 URL 路径（`/database`）提供，也可以通过 `database` 参数或属性提供。

- `database` 是可选项。未选中 database 时，执行 collection 语句需要先执行 `USE database`，调用 `Connection.setCatalog`/`setSchema` 选中 database，或使用 `database.collection` 这样的全限定 collection 名称。

- `consistencyLevel` 控制 query 和 search 请求使用的一致性级别。默认值为 `STRONG`。

- `defaultQueryLimit` 用于控制没有显式 `LIMIT` 的标量 `SELECT` 自动追加的 `LIMIT` 值。默认值为 `16384`，可配置范围为 `1` 到 `16384`。

### 离线 Jar

`mvn clean package` 会构建两个 jar：

- `target/milvus-jdbc-driver-0.1.0.jar`：普通驱动 jar，需要在 classpath 中额外提供依赖。
- `target/milvus-jdbc-driver-0.1.0-all.jar`：离线 jar，包含 Milvus Java SDK、ANTLR runtime 和 SLF4J。

对于只接受 JDBC driver jar 的工具，请使用 `-all.jar`。驱动类名：`io.github.jilstingray.milvus.jdbc.MilvusDriver`。

```xml
<dependency>
  <groupId>io.github.jilstingray</groupId>
  <artifactId>milvus-jdbc-driver</artifactId>
  <version>0.1.0</version>
  <classifier>all</classifier>
</dependency>
```

## Milvus 数据模型映射

Milvus 不是关系型数据库，该驱动会向 JDBC 工具暴露一个兼容关系型术语的视图：

| JDBC / 关系型术语 | Milvus 数据结构 | 驱动行为 |
| --- | --- | --- |
| Database | Database | Milvus database 是连接作用域。每个 Milvus database 建议使用独立 JDBC 连接。 |
| Schema | 没有独立命名空间 | Milvus 中的 "schema" 指 collection schema，即 collection 的字段定义。为了兼容 JDBC 元数据，该驱动把当前连接的 database 也作为 JDBC schema 返回。 |
| Table | Collection | 在支持的语句中，SQL table 操作会映射为 Milvus collection 操作。 |
| Column | Field | JDBC column 元数据来自 Milvus collection field。 |
| Row / record | Entity | 插入的行会作为 Milvus entity 写入。 |

Milvus 官方术语可参考：[数据库](https://milvus.io/docs/zh/manage_databases.md)、[集合](https://milvus.io/docs/zh/manage-collections.md)、[Schema](https://milvus.io/docs/zh/schema.md)。

### Table 与 Collection 是否完全等价

在该驱动中，从数据模型和执行层看，SQL table 表示 Milvus collection。这个映射是有意设计的，但不代表 `TABLE` 和 `COLLECTION` 两个关键字可以在所有 SQL 语句中任意互换。

等价或近似等价的形式：

- `SHOW TABLES` 和 `SHOW COLLECTIONS` 都会列出 Milvus collections。
- `DROP TABLE name` 和 `DROP COLLECTION name` 都会删除 Milvus collection。
- `CREATE TABLE name (...)` 和 `CREATE COLLECTION name DIMENSION n` 都会创建 Milvus collection，但 `CREATE TABLE` 用于显式定义字段 schema，`CREATE COLLECTION ... DIMENSION` 是更紧凑的快捷形式。
- `ALTER TABLE name RENAME TO new_name` 会重命名 Milvus collection。
- JDBC 元数据会把 Milvus collections 暴露为 tables，表类型为 `COLLECTION`。

需要注意的差异：

- `DESCRIBE name`、`DESCRIBE COLLECTION name`、`DESCRIBE TABLE name`、`DESC TABLE name` 和 `SHOW TABLE name` 都用于描述 Milvus collection。
- `SELECT`、`INSERT`、`UPSERT`、`COUNT`、`FLUSH`、`UPDATE` 直接使用对象名，例如 `SELECT * FROM books`；这些语句不会在 `FROM` 后使用 `TABLE` 或 `COLLECTION` 关键字。
- `database.collection` 这样的两段式引用表示 `Milvus database + collection`，不是 `schema.table`。database 前缀必须与当前 JDBC 连接绑定的 database 一致。
- 不支持 `database.schema.table` 这种三段式关系型名称，因为 Milvus 没有独立的关系型 schema 命名空间。

## SQL 支持

命令类别：

- Database：`CREATE/ALTER/DROP DATABASE`、`SHOW DATABASES`、`USE database`
- Collections/tables：`CREATE TABLE`、`CREATE COLLECTION ... DIMENSION`、`DROP TABLE`、`SHOW TABLES`、`SHOW CREATE TABLE`、`ALTER TABLE ... RENAME TO ...`
- Partitions：`CREATE/DROP PARTITION`、`SHOW PARTITION(S)`
- Aliases：`CREATE/ALTER/DROP ALIAS`
- Indexes：`CREATE/DROP INDEX`、`SHOW INDEX(ES)`
- RBAC：`CREATE/DROP USER`、`CREATE/DROP ROLE`、`GRANT/REVOKE ROLE`、`GRANT/REVOKE privilege`
- Data：`INSERT`、`UPSERT`、`UPDATE`、`DELETE`、`COUNT`、标量 `SELECT`、向量搜索 `SELECT ... ORDER BY vector <-> [...]`
- Memory/control：`LOAD TABLE`、`RELEASE TABLE`、`FLUSH`

示例：

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

没有显式 `LIMIT` 的标量 `SELECT` 默认使用连接的 `defaultQueryLimit` 值，因为 Milvus 对空表达式查询要求提供 limit。向量搜索会把 `ORDER BY vector <-> [...]` 映射为 SDK search 调用；标量 `SELECT` 映射为 query。

`UPDATE` 的标量查询更新必须带 `WHERE` 条件，不能更新主键字段，并支持 `UPDATE ... ORDER BY vector <-> [...] LIMIT ...` 向量搜索更新。如果标量查询更新没有显式 `LIMIT`，驱动会先统计匹配数量，超过 `defaultQueryLimit` 时会拒绝执行。标量字段更新使用 Milvus partial upsert。向量字段更新使用完整行 upsert：驱动会查询匹配实体，把 `SET` 值合并到原实体中，再 upsert 完整行，以确保向量替换可靠持久化。

`IMPORT` / BulkWriter / MinIO 文件导入命令，以及 `SHOW PROGRESS`、`SHOW GRANTS` 目前不受支持。后两者可以被解析，但会返回 `SQLFeatureNotSupportedException`。
