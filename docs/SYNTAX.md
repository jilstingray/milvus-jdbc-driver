# Milvus JDBC SQL Syntax

[中文语法手册](SYNTAX.zh.md)

This document describes the SQL grammar accepted by the Milvus JDBC driver parser. The grammar is implemented in `src/main/antlr4/io/github/jilstingray/milvus/jdbc/parser/MilvusJdbcParser.g4` and `MilvusJdbcLexer.g4`.

Some statements are parsed for compatibility but may still return `SQLFeatureNotSupportedException` at execution time. See the README SQL support section for execution notes.

## Lexical Rules

- Keywords are case-insensitive.
- A trailing semicolon is optional.
- Single-line comments start with `--`.
- Block comments use `/* ... */`.
- JDBC parameters use `?` where the grammar accepts `ARG`.
- String literals can use single quotes or double quotes. Escape by doubling the quote (`'it''s'`) or by using a backslash escape.
- Bare identifiers start with a letter or `_`, then may contain letters, digits, `_`, `.`, or `-`.
- Backtick identifiers are supported, for example `` `field-name` ``.
- Two-part names such as `database.collection` are parsed as one identifier.

## Notation

- `[x]` means optional.
- `x...` means one or more repeated items separated by commas when shown in comma-list form.
- Literal SQL keywords are written in uppercase for readability, but matching is case-insensitive.

## Database Statements

```sql
USE database

CREATE DATABASE [IF NOT EXISTS] database

ALTER DATABASE database SET PROPERTIES (property = value [, property = value]...)

DROP DATABASE [IF EXISTS] database

SHOW DATABASES
```

## Collection and Table Statements

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

`TABLE` maps to a Milvus collection in supported driver operations. `CREATE COLLECTION ... DIMENSION` is a compact shortcut; `CREATE TABLE (...)` defines explicit fields.

## Field Types

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

## Field Constraints

```sql
PRIMARY KEY
NOT NULL
DEFAULT string_or_identifier_or_number
COMMENT 'text'
AUTO_ID
```

Example:

```sql
CREATE TABLE books (
  id INT64 PRIMARY KEY AUTO_ID,
  title VARCHAR(512),
  embedding FLOAT_VECTOR(768),
  metadata JSON
) WITH ('description' = 'book collection')
```

## Partition Statements

```sql
CREATE PARTITION [IF NOT EXISTS] partition ON [TABLE] collection

DROP PARTITION [IF EXISTS] partition ON [TABLE] collection

SHOW PARTITIONS FROM [TABLE] collection

SHOW PARTITION partition ON [TABLE] collection
```

## Index Statements

```sql
CREATE INDEX [index_name] ON [TABLE] collection (field)
  [USING index_algorithm]
  [WITH (property = value [, property = value]...)]

DROP INDEX index_name ON [TABLE] collection

SHOW INDEXES FROM [TABLE] collection

SHOW INDEX index_name ON [TABLE] collection

SHOW PROGRESS OF INDEX [index_name] ON [TABLE] collection
```

`index_algorithm` can be an identifier or string literal.

## Alias Statements

```sql
CREATE ALIAS alias FOR [TABLE] collection

ALTER ALIAS alias FOR [TABLE] collection

DROP ALIAS [IF EXISTS] alias
```

## RBAC Statements

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

## Insert and Upsert

```sql
INSERT INTO collection [PARTITION partition] (column [, column]...)
VALUES (value [, value]...) [, (value [, value]...)]...

UPSERT INTO collection [PARTITION partition] (column [, column]...)
VALUES (value [, value]...) [, (value [, value]...)]...
```

Examples:

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

The driver execution layer requires a `WHERE` filter for scalar deletes.

## Update

```sql
UPDATE collection
  [PARTITION partition]
  SET column = value [, column = value]...
  [WHERE expression]
  [ORDER BY sort_clause]
  [LIMIT integer]
```

Examples:

```sql
UPDATE books SET title = 'b' WHERE id = 1 LIMIT 1

UPDATE books
SET status = 'hit'
ORDER BY vector <-> [0.1, 0.2]
LIMIT 10
```

The driver execution layer requires either a scalar `WHERE` filter or a vector-search `ORDER BY` clause. Primary key fields cannot be updated.

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

Examples:

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

Scalar `SELECT` maps to Milvus query. Vector search is expressed with an `ORDER BY` distance operator.

## Count

```sql
COUNT FROM collection [PARTITION partition] [WHERE expression]
```

## Load, Release, and Flush

```sql
LOAD TABLE collection [PARTITION partition]

RELEASE TABLE collection [PARTITION partition]

FLUSH collection

SHOW PROGRESS OF LOADING ON [TABLE] collection [PARTITION partition]
```

## Sort Clauses

```sql
field [ASC | DESC]

field distance_operator vector_value
```

Distance operators:

```sql
<->
<=>
<#>
```

`vector_value` can be a list literal or `?`.

Examples:

```sql
ORDER BY id DESC

ORDER BY vector <-> [0.1, 0.2]

ORDER BY vector <=> ?
```

## Expressions

Expressions are accepted in `WHERE` clauses and support:

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

`AND` can also be written as `&&`; `OR` can also be written as `||`.

The driver normalizes single equals (`=`) to Milvus equality (`==`) when converting filter text for Milvus query/search APIs.

## Literals

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

List literals are used for vector values and array-like values. Nested lists are accepted by the grammar because list values are literals.

## Properties

```sql
(property_key = property_value [, property_key = property_value]...)
```

Property keys can be identifiers or string literals. Property values can be string literals, identifiers, signed numbers, `true`, or `false`.

Examples:

```sql
WITH ('metric_type' = 'COSINE', 'params' = '{"nlist":128}')

WITH (consistency_level = BOUNDED, round_decimal = 3)
```
