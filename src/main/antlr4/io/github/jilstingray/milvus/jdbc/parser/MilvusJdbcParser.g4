parser grammar MilvusJdbcParser;

options { tokenVocab=MilvusJdbcLexer; }

root: statement SEMI? EOF;

statement
    : createDatabase | alterDatabase | dropDatabase
    | createTable | createCollection | dropTable | dropCollection | renameTable
    | createPartition | dropPartition
    | createIndex | dropIndex
    | createAlias | alterAlias | dropAlias
    | createUser | dropUser | createRole | dropRole | grant | revoke
    | showDatabases | showCollections | showTables | describeCollection | showCreateTable
    | showPartitions | showPartition | showIndexes | showIndex | showUsers | showRoles | showGrants | showProgress
    | insert | upsert | delete | select | count
    | load | release | flush | update
    ;

createDatabase: CREATE DATABASE (IF NOT EXISTS)? dbName=identifier;
alterDatabase: ALTER DATABASE dbName=identifier SET PROPERTIES propertiesList;
dropDatabase: DROP DATABASE (IF EXISTS)? dbName=identifier;

createCollection: CREATE COLLECTION collectionName=identifier DIMENSION dimension=INTEGER;
createTable: CREATE TABLE (IF NOT EXISTS)? collectionName=identifier OPEN_PAREN fieldDefinition (COMMA fieldDefinition)* CLOSE_PAREN (WITH propertiesList)?;
dropTable: DROP TABLE (IF EXISTS)? collectionName=identifier;
dropCollection: DROP COLLECTION collectionName=identifier;
renameTable: ALTER TABLE collectionName=identifier RENAME TO newName=identifier;

createPartition: CREATE PARTITION (IF NOT EXISTS)? partitionName=identifier ON TABLE? collectionName=identifier;
dropPartition: DROP PARTITION (IF EXISTS)? partitionName=identifier ON TABLE? collectionName=identifier;

createIndex: CREATE INDEX indexName=identifier? ON TABLE? collectionName=identifier OPEN_PAREN fieldName=identifier CLOSE_PAREN (USING indexAlgo)? (WITH propertiesList)?;
dropIndex: DROP INDEX indexName=identifier ON TABLE? collectionName=identifier;

createAlias: CREATE ALIAS aliasName=identifier FOR TABLE? collectionName=identifier;
alterAlias: ALTER ALIAS aliasName=identifier FOR TABLE? collectionName=identifier;
dropAlias: DROP ALIAS (IF EXISTS)? aliasName=identifier;

createUser: CREATE USER (IF NOT EXISTS)? userName=identifier PASSWORD password=STRING_LITERAL;
dropUser: DROP USER (IF EXISTS)? userName=identifier;
createRole: CREATE ROLE (IF NOT EXISTS)? roleName=identifier;
dropRole: DROP ROLE (IF EXISTS)? roleName=identifier;
grant: GRANT ROLE roleName=identifier TO userName=identifier | GRANT privilege=identifier ON objectType=identifier (objectName=identifier | STAR) TO ROLE roleName=identifier;
revoke: REVOKE ROLE roleName=identifier FROM userName=identifier | REVOKE privilege=identifier ON objectType=identifier (objectName=identifier | STAR) FROM ROLE roleName=identifier;

showDatabases: SHOW DATABASES;
showCollections: SHOW COLLECTIONS;
showTables: SHOW TABLES;
describeCollection: (DESCRIBE | DESC) (COLLECTION | TABLE)? collectionName=identifier | SHOW TABLE collectionName=identifier;
showCreateTable: SHOW CREATE TABLE collectionName=identifier;
showPartitions: SHOW PARTITIONS FROM TABLE? collectionName=identifier;
showPartition: SHOW PARTITION partitionName=identifier ON TABLE? collectionName=identifier;
showIndexes: SHOW INDEXES FROM TABLE? collectionName=identifier;
showIndex: SHOW INDEX indexName=identifier ON TABLE? collectionName=identifier;
showUsers: SHOW USERS;
showRoles: SHOW ROLES;
showGrants: SHOW GRANTS FOR ROLE roleName=identifier (ON (TABLE | USER) objectName=identifier | ON GLOBAL)?;
showProgress: SHOW PROGRESS OF INDEX indexName=identifier? ON TABLE? collectionName=identifier | SHOW PROGRESS OF LOADING ON TABLE? collectionName=identifier (PARTITION partitionName=identifier)?;

insert: INSERT INTO collectionName=identifier (PARTITION partitionName=identifier)? OPEN_PAREN columnList=identifierList CLOSE_PAREN VALUES valueRows;
upsert: UPSERT INTO collectionName=identifier (PARTITION partitionName=identifier)? OPEN_PAREN columnList=identifierList CLOSE_PAREN VALUES valueRows;
valueRows: valueRow (COMMA valueRow)*;
valueRow: OPEN_PAREN valueList=literalList CLOSE_PAREN;

delete: DELETE FROM TABLE? collectionName=identifier (PARTITION partitionName=identifier)? (WHERE expression)? (ORDER BY sortClause)? (LIMIT limit=INTEGER)?;
update: UPDATE collectionName=identifier (PARTITION partitionName=identifier)? SET setClauseList (WHERE expression)? (ORDER BY sortClause)? (LIMIT limit=INTEGER)?;
setClauseList: setClause (COMMA setClause)*;
setClause: columnName=identifier EQUALS value=term;

select: SELECT constantSelectElements | SELECT selectElements FROM source=tableRef (PARTITION partitionName=identifier)? (WHERE expression)? (ORDER BY sortClause)? (LIMIT limit=INTEGER)? (OFFSET offset=INTEGER)? (WITH propertiesList)?;
count: COUNT FROM collectionName=identifier (PARTITION partitionName=identifier)? (WHERE expression)?;
load: LOAD TABLE collectionName=identifier (PARTITION partitionName=identifier)?;
release: RELEASE TABLE collectionName=identifier (PARTITION partitionName=identifier)?;
flush: FLUSH collectionName=identifier;

selectElements: STAR | qualifiedStar | identifierList;
constantSelectElements: literal (COMMA literal)*;
qualifiedStar: qualifier=identifier STAR;
tableRef: collectionName=identifier alias=identifier?;
identifierList: identifier (COMMA identifier)*;

sortClause: fieldName=identifier (ASC | DESC)? | fieldName=identifier distanceOperator vectorValue;
distanceOperator: LT_MINUS_GT | LT_EQ_GT | LT_HASH_GT;
vectorValue: listLiteral | ARG;

expression
    : OPEN_PAREN expression CLOSE_PAREN
    | NOT expression
    | expression (STAR | SLASH | MOD) expression
    | expression (PLUS | MINUS) expression
    | expression (GT | LT | GTE | LTE) expression
    | expression (EQ | EQUALS | NE) expression
    | expression (AND | OR) expression
    | identifier IN (listLiteral | OPEN_PAREN literalList CLOSE_PAREN | ARG)
    | identifier LIKE (STRING_LITERAL | ARG)
    | term
    ;
term: ARG | identifier | literal;
literalList: literal (COMMA literal)*;
literal: STRING_LITERAL | signedNumber | TRUE | FALSE | NULL | listLiteral | identifier;
signedNumber: (PLUS | MINUS)? (INTEGER | FLOAT_LITERAL);
listLiteral: OPEN_BRACKET literalList? CLOSE_BRACKET;
propertiesList: OPEN_PAREN property (COMMA property)* CLOSE_PAREN;
property: (identifier | STRING_LITERAL) EQUALS (STRING_LITERAL | identifier | signedNumber | TRUE | FALSE);
fieldDefinition: fieldName=identifier fieldType fieldConstraint*;
fieldType: BOOL | INT8 | INT16 | INT32 | INT64 | FLOAT | DOUBLE | JSON | VARCHAR OPEN_PAREN INTEGER CLOSE_PAREN | FLOAT_VECTOR OPEN_PAREN INTEGER CLOSE_PAREN | BINARY_VECTOR OPEN_PAREN INTEGER CLOSE_PAREN | FLOAT16_VECTOR OPEN_PAREN INTEGER CLOSE_PAREN | BFLOAT16_VECTOR OPEN_PAREN INTEGER CLOSE_PAREN | SPARSE_FLOAT_VECTOR OPEN_PAREN INTEGER CLOSE_PAREN | ARRAY;
fieldConstraint: PRIMARY KEY | NOT NULL | DEFAULT (STRING_LITERAL | IDENTIFIER | signedNumber) | COMMENT STRING_LITERAL | AUTO_ID;
indexAlgo: STRING_LITERAL | identifier;
identifier: IDENTIFIER | COLLECTION | COLLECTIONS | DATABASE | DATABASES | TABLE | TABLES | DIMENSION | VALUES | INDEX | INDEXES | PARTITION | PARTITIONS | USER | USERS | ROLE | ROLES | ALIAS | PROGRESS | LOADING | OF | METRIC | PARAMS | ROUND_DECIMAL | CONSISTENCY_LEVEL | VECTOR | SELECT | INSERT | DELETE | UPDATE | CREATE | DROP | GRANT | REVOKE | PRIVILEGE | LOAD | RELEASE | FLUSH | COUNT;
