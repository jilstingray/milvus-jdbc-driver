# Milvus JDBC SQL 语法手册

[English syntax manual](SYNTAX.md)

本文说明 Milvus JDBC Driver 解析器接受的 SQL 语法。语法定义位于 `src/main/antlr4/io/github/jilstingray/milvus/jdbc/parser/MilvusJdbcParser.g4` 和 `MilvusJdbcLexer.g4`。

部分语句出于兼容性可以被解析，但执行时可能仍返回 `SQLFeatureNotSupportedException`。执行层支持情况请参考 README 的 SQL 支持章节。

## 词法规则

- 关键字大小写不敏感。
- 语句末尾的分号可选。
- 单行注释以 `--` 开始。
- 块注释使用 `/* ... */`。
- JDBC 参数使用 `?`，对应语法中的 `ARG`。
- 字符串字面量可使用单引号或双引号。可通过双写引号（`'it''s'`）或反斜杠转义。
- 普通标识符以字母或 `_` 开头，后续可包含字母、数字、`_`、`.` 或 `-`。
- 支持反引号标识符，例如 `` `field-name` ``。
- `database.collection` 这样的两段式名称会被解析为一个标识符。

## 记法

- `[x]` 表示可选。
- `x...` 表示一个或多个重复项；在逗号列表中表示用逗号分隔。
- SQL 关键字用大写书写只是为了便于阅读，实际匹配大小写不敏感。

## Database 语句

```sql
USE database

CREATE DATABASE [IF NOT EXISTS] database

ALTER DATABASE database SET PROPERTIES (property = value [, property = value]...)

DROP DATABASE [IF EXISTS] database

SHOW DATABASES
```

## Collection 和 Table 语句

```sql
CREATE COLLECTION collection DIMENSION integer

CREATE TABLE [IF NOT EXISTS] collection (
  field_name field_type [field_constraint]...
  [, field_name field_type [field_constraint]...]...
) [WITH (property = value [, property = value]...)]

DROP TABLE [IF EXISTS] collection

DROP COLLECTION collection

ALTER TABLE collection RENAME TO new_collection

SHOW COLLECTIONS

SHOW TABLES

DESCRIBE [COLLECTION | TABLE] collection

DESC [COLLECTION | TABLE] collection

SHOW TABLE collection

SHOW CREATE TABLE collection
```

在驱动支持的操作中，`TABLE` 映射到 Milvus collection。`CREATE COLLECTION ... DIMENSION` 是紧凑快捷形式；`CREATE TABLE (...)` 用于显式定义字段。

## 字段类型

```sql
BOOL
INT8
INT16
INT32
INT64
FLOAT
DOUBLE
JSON
VARCHAR(integer)
FLOAT_VECTOR(integer)
BINARY_VECTOR(integer)
FLOAT16_VECTOR(integer)
BFLOAT16_VECTOR(integer)
SPARSE_FLOAT_VECTOR(integer)
ARRAY
```

## 字段约束

```sql
PRIMARY KEY
NOT NULL
DEFAULT string_or_identifier_or_number
COMMENT 'text'
AUTO_ID
```

示例：

```sql
CREATE TABLE books (
  id INT64 PRIMARY KEY AUTO_ID,
  title VARCHAR(512),
  embedding FLOAT_VECTOR(768),
  metadata JSON
) WITH ('description' = 'book collection')
```

## Partition 语句

```sql
CREATE PARTITION [IF NOT EXISTS] partition ON [TABLE] collection

DROP PARTITION [IF EXISTS] partition ON [TABLE] collection

SHOW PARTITIONS FROM [TABLE] collection

SHOW PARTITION partition ON [TABLE] collection
```

## Index 语句

```sql
CREATE INDEX [index_name] ON [TABLE] collection (field)
  [USING index_algorithm]
  [WITH (property = value [, property = value]...)]

DROP INDEX index_name ON [TABLE] collection

SHOW INDEXES FROM [TABLE] collection

SHOW INDEX index_name ON [TABLE] collection

SHOW PROGRESS OF INDEX [index_name] ON [TABLE] collection
```

`index_algorithm` 可以是标识符或字符串字面量。

## Alias 语句

```sql
CREATE ALIAS alias FOR [TABLE] collection

ALTER ALIAS alias FOR [TABLE] collection

DROP ALIAS [IF EXISTS] alias
```

## RBAC 语句

```sql
CREATE USER [IF NOT EXISTS] user PASSWORD 'password'

DROP USER [IF EXISTS] user

CREATE ROLE [IF NOT EXISTS] role

DROP ROLE [IF EXISTS] role

GRANT ROLE role TO user

REVOKE ROLE role FROM user

GRANT privilege ON object_type (object_name | *) TO ROLE role

REVOKE privilege ON object_type (object_name | *) FROM ROLE role

SHOW USERS

SHOW ROLES
```

## Insert 和 Upsert

```sql
INSERT INTO collection [PARTITION partition] (column [, column]...)
VALUES (value [, value]...) [, (value [, value]...)]...

UPSERT INTO collection [PARTITION partition] (column [, column]...)
VALUES (value [, value]...) [, (value [, value]...)]...
```

示例：

```sql
INSERT INTO books (id, vector, title)
VALUES (1, [0.1, 0.2], 'a')

UPSERT INTO books PARTITION p1 (id, vector, title)
VALUES (1, [0.1, 0.2], 'updated')
```

## Delete

```sql
DELETE FROM [TABLE] collection
  [PARTITION partition]
  [WHERE expression]
  [ORDER BY sort_clause]
  [LIMIT integer]
```

驱动执行层要求标量删除必须带 `WHERE` 过滤条件。

## Update

```sql
UPDATE collection
  [PARTITION partition]
  SET column = value [, column = value]...
  [WHERE expression]
  [ORDER BY sort_clause]
  [LIMIT integer]
```

示例：

```sql
UPDATE books SET title = 'b' WHERE id = 1 LIMIT 1

UPDATE books
SET status = 'hit'
ORDER BY vector <-> [0.1, 0.2]
LIMIT 10
```

驱动执行层要求提供标量 `WHERE` 过滤条件，或提供向量搜索 `ORDER BY` 子句。主键字段不能被更新。

## Select

```sql
SELECT literal [, literal]...

SELECT (* | qualifier.* | column [, column]...)
FROM collection [alias]
  [PARTITION partition]
  [WHERE expression]
  [ORDER BY sort_clause]
  [LIMIT integer]
  [OFFSET integer]
  [WITH (property = value [, property = value]...)]
```

示例：

```sql
SELECT 'keep alive'

SELECT * FROM books

SELECT id, title FROM books WHERE id < 10 LIMIT 5

SELECT t.* FROM default.books t

SELECT id, title
FROM books
ORDER BY vector <-> [0.1, 0.2]
LIMIT 10
```

标量 `SELECT` 会映射到 Milvus query。向量搜索通过 `ORDER BY` 距离操作符表达。

## Count

```sql
COUNT FROM collection [PARTITION partition] [WHERE expression]
```

## Load、Release 和 Flush

```sql
LOAD TABLE collection [PARTITION partition]

RELEASE TABLE collection [PARTITION partition]

FLUSH collection

SHOW PROGRESS OF LOADING ON [TABLE] collection [PARTITION partition]
```

## 排序子句

```sql
field [ASC | DESC]

field distance_operator vector_value
```

距离操作符：

```sql
<->
<=>
<#>
```

`vector_value` 可以是列表字面量或 `?`。

示例：

```sql
ORDER BY id DESC

ORDER BY vector <-> [0.1, 0.2]

ORDER BY vector <=> ?
```

## 表达式

表达式可用于 `WHERE` 子句，支持：

```sql
(expression)
NOT expression
expression * expression
expression / expression
expression % expression
expression + expression
expression - expression
expression > expression
expression < expression
expression >= expression
expression <= expression
expression = expression
expression == expression
expression != expression
expression <> expression
expression AND expression
expression OR expression
identifier IN [value [, value]...]
identifier IN (value [, value]...)
identifier IN ?
identifier LIKE 'pattern'
identifier LIKE ?
term
```

`AND` 也可以写作 `&&`；`OR` 也可以写作 `||`。

驱动在把过滤条件转换为 Milvus query/search API 文本时，会把单等号（`=`）规范化为 Milvus 等值操作符（`==`）。

## 字面量

```sql
'string'
"string"
+123
-123
123
3.14
.5
1e-5
true
false
null
[value [, value]...]
identifier
?
```

列表字面量可用于向量值和类似数组的值。由于列表项本身也是字面量，语法上也接受嵌套列表。

## Properties

```sql
(property_key = property_value [, property_key = property_value]...)
```

property key 可以是标识符或字符串字面量。property value 可以是字符串字面量、标识符、有符号数字、`true` 或 `false`。

示例：

```sql
WITH ('metric_type' = 'COSINE', 'params' = '{"nlist":128}')

WITH (consistency_level = BOUNDED, round_decimal = 3)
```
