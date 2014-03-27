/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.kazuki.v0.store.index;

import io.kazuki.v0.internal.hash.LongHash;
import io.kazuki.v0.internal.hash.MurmurHash;
import io.kazuki.v0.internal.helper.OpaquePaginationHelper;
import io.kazuki.v0.internal.helper.SqlParamBindings;
import io.kazuki.v0.internal.helper.SqlTypeHelper;
import io.kazuki.v0.internal.helper.StringHelper;
import io.kazuki.v0.internal.v2schema.compact.FieldTransform;
import io.kazuki.v0.store.KazukiException;
import io.kazuki.v0.store.index.query.QueryOperator;
import io.kazuki.v0.store.index.query.QueryTerm;
import io.kazuki.v0.store.index.query.ValueHolder;
import io.kazuki.v0.store.index.query.ValueType;
import io.kazuki.v0.store.keyvalue.KeyValueStoreIteration.SortDirection;
import io.kazuki.v0.store.schema.model.Attribute;
import io.kazuki.v0.store.schema.model.IndexAttribute;
import io.kazuki.v0.store.schema.model.IndexDefinition;
import io.kazuki.v0.store.schema.model.Schema;
import io.kazuki.v0.store.sequence.KeyImpl;
import io.kazuki.v0.store.sequence.SequenceService;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.IDBI;
import org.skife.jdbi.v2.Query;
import org.skife.jdbi.v2.TransactionCallback;
import org.skife.jdbi.v2.TransactionStatus;
import org.skife.jdbi.v2.exceptions.UnableToExecuteStatementException;

import com.google.inject.Inject;

public class SecondaryIndexTableHelper {
  private final SqlTypeHelper typeHelper;
  private final LongHash longHash;
  private final SequenceService sequences;
  private final String prefix;

  @Inject
  public SecondaryIndexTableHelper(SqlTypeHelper typeHelper, SequenceService sequences) {
    this.typeHelper = typeHelper;
    this.longHash = new MurmurHash();
    this.sequences = sequences;
    this.prefix = typeHelper.getPrefix();
  }

  public String getPrefix() {
    return prefix;
  }

  public void createIndex(IDBI database, final String type, final String indexName,
      final Schema schema) {
    database.inTransaction(new TransactionCallback<Void>() {
      @Override
      public Void inTransaction(Handle handle, TransactionStatus arg1) throws Exception {
        try {
          handle.createStatement(prefix + "drop_index")
              .define("table_name", getTableName(type, indexName))
              .define("index_name", getIndexName(type, indexName)).execute();
        } catch (UnableToExecuteStatementException ok) {
          // expected case in mysql - this is just best-effort anyway
        }

        handle.createStatement(getTableDefinition(type, indexName, schema)).execute();

        handle.createStatement(getIndexDefinition(type, indexName, schema)).execute();

        return null;
      }
    });
  }

  public void dropTableAndIndex(Handle handle, final String type, final String indexName) {
    handle.createStatement(getTableDrop(type, indexName)).execute();

    try {
      handle.createStatement(prefix + "drop_index")
          .define("table_name", getTableName(type, indexName))
          .define("index_name", getIndexName(type, indexName)).execute();
    } catch (UnableToExecuteStatementException ok) {
      // expected case in mysql - this is just best-effort anyway
    }
  }

  public void dropTableAndIndex(IDBI database, final String type, final String indexName) {
    database.inTransaction(new TransactionCallback<Void>() {
      @Override
      public Void inTransaction(Handle handle, TransactionStatus arg1) throws Exception {
        dropTableAndIndex(handle, type, indexName);

        return null;
      }
    });
  }

  public String getInsertStatement(String type, String indexName, Schema schema,
      SqlParamBindings bindings) {
    IndexDefinition indexDefinition = schema.getIndexMap().get(indexName);

    List<String> cols = new ArrayList<String>();
    List<String> params = new ArrayList<String>();
    Set<String> already = new HashSet<String>();

    cols.add(getColumnName("id"));
    params.add(bindings.bind("id", Attribute.Type.U64));
    already.add("id");

    for (IndexAttribute attr : indexDefinition.getIndexAttributes()) {
      if (already.contains(attr.getName())) {
        continue;
      }

      cols.add(getColumnName(attr.getName()));
      params.add(bindings.bind(attr.getName(), schema.getAttribute(attr.getName()).getType()));
    }

    cols.add(typeHelper.quote("quarantined"));
    params.add(bindings.bind("quarantined", "N", Attribute.Type.CHAR_ONE));

    StringBuilder sqlBuilder = new StringBuilder();
    sqlBuilder.append("insert into ");
    sqlBuilder.append(getTableName(type, indexName));
    sqlBuilder.append(" (");
    sqlBuilder.append(StringHelper.join(", ", cols));
    sqlBuilder.append(") values (");
    sqlBuilder.append(StringHelper.join(", ", params));
    sqlBuilder.append(")");

    return sqlBuilder.toString();
  }

  public String getUpdateStatement(String type, String indexName, Schema schema,
      SqlParamBindings bindings) {
    IndexDefinition indexDefinition = schema.getIndexMap().get(indexName);

    List<String> sets = new ArrayList<String>();

    for (IndexAttribute attr : indexDefinition.getIndexAttributes()) {
      if ("id".equals(attr.getName())) {
        continue;
      }

      sets.add(getColumnName(attr.getName()) + " = "
          + bindings.bind(attr.getName(), schema.getAttribute(attr.getName()).getType()));
    }

    StringBuilder sqlBuilder = new StringBuilder();
    sqlBuilder.append("update ");
    sqlBuilder.append(getTableName(type, indexName));
    sqlBuilder.append(" set ");
    sqlBuilder.append(StringHelper.join(", ", sets));
    sqlBuilder.append(" where ");
    sqlBuilder.append(typeHelper.quote("_id"));
    sqlBuilder.append(" = ");
    sqlBuilder.append(bindings.bind("id", Attribute.Type.U64));

    return sqlBuilder.toString();
  }

  public String getDeleteStatement(String type, String indexName, SqlParamBindings bindings) {
    StringBuilder sqlBuilder = new StringBuilder();
    sqlBuilder.append("delete from ");
    sqlBuilder.append(getTableName(type, indexName));
    sqlBuilder.append(" where ");
    sqlBuilder.append(typeHelper.quote("_id"));
    sqlBuilder.append(" = ");
    sqlBuilder.append(bindings.bind("id", Attribute.Type.U64));

    return sqlBuilder.toString();
  }

  public String getQuarantineStatement(String type, String indexName, SqlParamBindings bindings,
      boolean isQuarantined) {
    StringBuilder sqlBuilder = new StringBuilder();
    sqlBuilder.append("update ");
    sqlBuilder.append(getTableName(type, indexName));
    sqlBuilder.append(" set ");
    sqlBuilder.append(typeHelper.quote("quarantined"));
    sqlBuilder.append(" = ");
    sqlBuilder.append(bindings.bind("is_quarantined", isQuarantined ? "Y" : "N",
        Attribute.Type.CHAR_ONE));
    sqlBuilder.append(" where ");
    sqlBuilder.append(typeHelper.quote("_id"));
    sqlBuilder.append(" = ");
    sqlBuilder.append(bindings.bind("id", Attribute.Type.U64));

    return sqlBuilder.toString();
  }

  public void truncateIndexTable(Handle handle, final String type, final String indexName) {
    String indexTableName = getTableName(type, indexName);
    handle.createStatement(prefix + "truncate_table").define("table_name", indexTableName)
        .execute();
  }

  public String getSqlOperator(QueryOperator operator, ValueHolder value) {
    switch (operator) {
      case EQ:
        return (value.getValueType().equals(ValueType.NULL)) ? "is null" : "=";
      case NE:
        return (value.getValueType().equals(ValueType.NULL)) ? "is not null" : "<>";
      case GT:
        return ">";
      case GE:
        return ">=";
      case LT:
        return "<";
      case LE:
        return "<=";
      case IN:
        return "in";
      default:
        throw new IllegalArgumentException("Unknown operator: " + operator);
    }
  }

  public Object transformAttributeValue(Object value, IndexAttribute attr) {
    Object toBind = value;

    if (toBind != null) {
      switch (attr.getTransform()) {
        case UPPERCASE:
          toBind = toBind.toString().toUpperCase();
          break;
        case LOWERCASE:
          toBind = toBind.toString().toLowerCase();
          break;
        default:
          break;
      }
    }

    return toBind;
  }

  public boolean isConstraintViolation(UnableToExecuteStatementException e) {
    if (e.getCause() != null) {
      String message = e.getCause().getMessage().toLowerCase();

      return message.contains("constraint violation") || message.contains("duplicate entry")
          || message.contains("unique index or primary key violation");
    }

    return false;
  }

  public String getColumnName(String attributeName, boolean doQuote) {
    return doQuote ? getColumnName(attributeName) : "_" + attributeName;
  }

  public String getColumnName(String attributeName) {
    return typeHelper.quote("_" + attributeName + "");
  }

  public String getTableName(String type, String index) {
    try {
      Integer typeId = sequences.getTypeId(type, false);
      if (typeId == null) {
        return null;
      }

      return typeHelper.quote("_i_" + String.format("%04d", typeId) + "__" + getIndexHexId(index));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public String getIndexName(String type, String index) {
    try {
      return typeHelper.quote("_idx_" + getUniqueIndexIdentifier(type, index));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private String getIndexHexId(String index) {
    return String.format("%016x", longHash.getLongHashCode(index));
  }

  public String getTableDrop(String type, String indexName) {
    StringBuilder sqlBuilder = new StringBuilder();
    sqlBuilder.append("drop table if exists ");
    sqlBuilder.append(getTableName(type, indexName));

    return sqlBuilder.toString();
  }

  public String getIndexDrop(String type, String indexName) {
    StringBuilder sqlBuilder = new StringBuilder();
    sqlBuilder.append("drop index ");
    sqlBuilder.append(getIndexName(type, indexName));
    sqlBuilder.append(" on ");
    sqlBuilder.append(getTableName(type, indexName));

    return sqlBuilder.toString();
  }

  public String getTableDefinition(String type, String indexName, Schema schema) {
    IndexDefinition indexDefinition = schema.getIndexMap().get(indexName);

    if (indexDefinition == null) {
      throw new IllegalArgumentException("schema or index not found " + type + "." + indexName);
    }

    StringBuilder sqlBuilder = new StringBuilder();
    sqlBuilder.append("create table if not exists ");
    sqlBuilder.append(getTableName(type, indexName));
    sqlBuilder.append(" (");
    sqlBuilder.append(typeHelper.quote("_id"));
    sqlBuilder.append(" ");
    sqlBuilder.append(typeHelper.getSqlType(Attribute.Type.U64));
    sqlBuilder.append(" PRIMARY KEY");

    for (IndexAttribute column : indexDefinition.getIndexAttributes()) {
      Attribute attribute = schema.getAttribute(column.getName());
      if (attribute == null && !column.getName().equals("id")) {
        throw new IllegalArgumentException("Unknown attribute : " + column.getName());
      }

      if (column.getName().equals("id")) {
        continue;
      }

      sqlBuilder.append(", ");
      sqlBuilder.append(getColumnName(column.getName()));
      sqlBuilder.append(" ");
      sqlBuilder.append(typeHelper.getSqlType(attribute.getType()));
    }

    sqlBuilder.append(", ");
    sqlBuilder.append(typeHelper.quote("quarantined"));
    sqlBuilder.append(" ");
    sqlBuilder.append(typeHelper.getSqlType(Attribute.Type.CHAR_ONE));

    sqlBuilder.append(")");
    sqlBuilder.append(typeHelper.getTableOptions());

    return sqlBuilder.toString();
  }

  public String getIndexDefinition(String type, String indexName, Schema schema) {
    IndexDefinition indexDefinition = schema.getIndexMap().get(indexName);

    if (indexDefinition == null) {
      throw new IllegalArgumentException("schema or index not found " + type + "." + indexName);
    }

    Iterator<IndexAttribute> iter = indexDefinition.getIndexAttributes().iterator();

    List<String> colDefs = new ArrayList<String>();
    while (iter.hasNext()) {
      IndexAttribute column = iter.next();

      Attribute attribute = schema.getAttribute(column.getName());

      if (attribute == null && !column.getName().equals("id")) {
        throw new IllegalArgumentException("Unknown attribute : " + column.getName());
      }

      if (indexDefinition.isUnique() && column.getName().equals("id")) {
        continue;
      }

      String sortDirection =
          column.getSortDirection().equals(SortDirection.ASCENDING) ? "ASC" : "DESC";

      colDefs.add(getColumnName(column.getName()) + " " + sortDirection);
    }

    colDefs.add(getColumnName("id") + " " + "ASC");

    StringBuilder sqlBuilder = new StringBuilder();
    sqlBuilder.append("create ");

    if (indexDefinition.isUnique()) {
      sqlBuilder.append("unique ");
    }

    sqlBuilder.append("index ");
    sqlBuilder.append(getIndexName(type, indexName));
    sqlBuilder.append(" on ");
    sqlBuilder.append(getTableName(type, indexName));
    sqlBuilder.append(" (");
    sqlBuilder.append(StringHelper.join(", ", colDefs));
    sqlBuilder.append(")");

    return sqlBuilder.toString();
  }

  public String getIndexQuery(String type, String indexName, SortDirection sortDirection,
      List<QueryTerm> queryTerms, String token, Long pageSize, boolean includeQuarantine,
      Schema schema, SqlParamBindings bindings) throws Exception {
    IndexDefinition indexDef = schema.getIndex(indexName);

    if (indexDef == null) {
      throw new IllegalArgumentException("schema or index not found " + type + "." + indexName);
    }

    return getIndexQuery(type, indexName, sortTerms(indexDef, queryTerms), sortDirection, token,
        pageSize, includeQuarantine, indexDef, schema, new FieldTransform(schema), bindings);

  }

  public String getIndexQuery(String type, String indexName, Map<String, List<QueryTerm>> termMap,
      SortDirection sortDirection, String token, Long pageSize, boolean includeQuarantine,
      IndexDefinition indexDefinition, Schema schema, FieldTransform transform,
      SqlParamBindings bindings) throws Exception {
    List<QueryTerm> firstTerm = termMap.get(indexDefinition.getIndexAttributes().get(0).getName());
    if (firstTerm == null || firstTerm.isEmpty()) {
      throw new IllegalArgumentException("missing query term for first attribute of index");
    }

    List<String> clauses = new ArrayList<String>();
    int param = 0;

    for (IndexAttribute attribute : indexDefinition.getIndexAttributes()) {
      String attrName = attribute.getName();
      List<QueryTerm> termList = termMap.get(attrName);

      if (termList == null || termList.isEmpty()) {
        continue;
      }

      for (QueryTerm term : termList) {
        String maybeParam = "";
        QueryOperator op = term.getOperator();

        if (op.equals(QueryOperator.IN)) {
          List<ValueHolder> valueList = term.getValueList().getValueList();

          String sqlOperator = getSqlOperator(term.getOperator(), valueList.get(0));

          List<String> paramNames = new ArrayList<String>();

          for (ValueHolder value : valueList) {
            String boundParam =
                bindParam(attribute, schema, transform, bindings, param, attrName, value);

            if (boundParam != null) {
              maybeParam = " " + boundParam;
              paramNames.add(maybeParam);
              param += 1;
            } else {
              maybeParam = "";
            }
          }

          clauses.add(getColumnName(term.getField()) + " " + sqlOperator + "("
              + StringHelper.join(", ", paramNames) + ")");
        } else {
          String boundParam =
              bindParam(attribute, schema, transform, bindings, param, attrName, term.getValue());

          if (boundParam != null) {
            maybeParam = " " + boundParam;
            param += 1;
          }

          clauses.add(getColumnName(term.getField()) + " "
              + getSqlOperator(term.getOperator(), term.getValue()) + maybeParam);
        }
      }
    }

    List<String> sortOrders = new ArrayList<String>();
    for (IndexAttribute attr : indexDefinition.getIndexAttributes()) {
      String colName = getColumnName(attr.getName());

      String colSortDirection = null;

      if (sortDirection.equals(attr.getSortDirection())) {
        colSortDirection = sortDirection.equals(SortDirection.ASCENDING) ? "ASC" : "DESC";
      } else {
        colSortDirection = sortDirection.equals(SortDirection.DESCENDING) ? "DESC" : "ASC";
      }

      sortOrders.add(colName + " " + colSortDirection);
    }

    sortOrders.add(getColumnName("id") + " "
        + (sortDirection.equals(SortDirection.ASCENDING) ? "ASC" : "DESC"));

    Long offset = token != null ? OpaquePaginationHelper.decodeOpaqueCursor(token) : 0L;
    Long limit = pageSize != null ? pageSize + 1L : -1;

    StringBuilder sqlBuilder = new StringBuilder();
    sqlBuilder.append("select ");
    sqlBuilder.append(typeHelper.quote("_id"));
    sqlBuilder.append(" from ");
    sqlBuilder.append(getTableName(type, indexName));
    sqlBuilder.append(" where ");
    if (!includeQuarantine) {
      sqlBuilder.append(typeHelper.quote("quarantined"));
      sqlBuilder.append(" = 'N' AND ");
    }
    sqlBuilder.append(StringHelper.join(" AND ", clauses));
    sqlBuilder.append(" order by ");
    sqlBuilder.append(StringHelper.join(", ", sortOrders));
    sqlBuilder.append(" limit ");
    sqlBuilder.append(limit);
    sqlBuilder.append(" offset ");
    sqlBuilder.append(offset);

    return sqlBuilder.toString();
  }

  public String getIndexAllQuery(String type, String token, Long pageSize, boolean includeQuarantine)
      throws Exception {
    StringBuilder sqlBuilder = new StringBuilder();
    sqlBuilder.append("select ");
    sqlBuilder.append(typeHelper.quote("_key_id"));
    sqlBuilder.append(" as ");
    sqlBuilder.append(typeHelper.quote("_id"));
    sqlBuilder.append(" from ");
    sqlBuilder.append(typeHelper.quote("_key_values"));
    sqlBuilder.append(" where ");
    sqlBuilder.append(typeHelper.quote("_key_type"));
    sqlBuilder.append(" = ");
    sqlBuilder.append(sequences.getTypeId(type, false));
    sqlBuilder.append(" AND ");
    if (!includeQuarantine) {
      sqlBuilder.append(typeHelper.quote("_is_deleted"));
      sqlBuilder.append(" = 'N'");
    } else {
      sqlBuilder.append(typeHelper.quote("_is_deleted"));
      sqlBuilder.append(" != 'Y'");
    }
    sqlBuilder.append(" order by ");
    sqlBuilder.append(typeHelper.quote("_id"));
    sqlBuilder.append(" limit ");
    sqlBuilder.append(pageSize + 1L);
    sqlBuilder.append(" offset ");
    sqlBuilder.append(OpaquePaginationHelper.decodeOpaqueCursor(token));

    return sqlBuilder.toString();
  }

  public List<Map<String, Object>> doUniqueIndexQuery(IDBI database, String type, String indexName,
      Map<String, List<QueryTerm>> termMap, String token, IndexDefinition indexDefinition,
      Schema schema, FieldTransform transform) throws Exception {
    if (token != null) {
      return null;
    }

    Set<String> foundAttrs = new HashSet<String>();
    for (Map.Entry<String, List<QueryTerm>> entry : termMap.entrySet()) {
      if (entry.getValue().size() != 1) {
        return null;
      }

      if (!QueryOperator.EQ.equals(entry.getValue().get(0).getOperator())) {
        return null;
      }

      foundAttrs.add(entry.getKey());
    }

    for (String attrName : indexDefinition.getAttributeNames()) {
      if (!foundAttrs.contains(attrName)) {
        return null;
      }
    }

    Map<String, Object> value = new LinkedHashMap<String, Object>();

    for (IndexAttribute attribute : indexDefinition.getIndexAttributes()) {
      String attrName = attribute.getName();
      QueryTerm term = termMap.get(attrName).get(0);
      Object termValue = term.getValue().getValue();
      value.put(attrName, transform.transformValue(attrName, termValue));
    }

    final List<Map<String, Object>> resultIds = new ArrayList<Map<String, Object>>();
    final SqlParamBindings bindings = new SqlParamBindings(true);

    final String querySql =
        getIndexQuery(type, indexName, termMap, null, token, 10L, false, indexDefinition, schema,
            transform, bindings);

    database.inTransaction(new TransactionCallback<Void>() {
      @Override
      public Void inTransaction(Handle handle, TransactionStatus status) throws Exception {
        Query<Map<String, Object>> query = handle.createQuery(querySql);

        bindings.bindToStatement(query);

        List<Map<String, Object>> qList = query.list();
        if (qList == null || qList.size() > 1) {
          throw new RuntimeException("Non-unique result set!");
        }

        if (qList.size() == 1) {
          resultIds.add(query.first());
        }

        return null;
      }
    });

    return Collections.unmodifiableList(resultIds);
  }

  public String computeIndexKey(String type, String indexName, IndexDefinition indexDefinition,
      Map<String, Object> value) {
    StringBuilder theKey = new StringBuilder();

    try {
      theKey.append("idx:");
      theKey.append(getUniqueIndexIdentifier(type, indexName));
      theKey.append(":");

      Iterator<IndexAttribute> indexAttrIter = indexDefinition.getIndexAttributes().iterator();

      while (indexAttrIter.hasNext()) {
        IndexAttribute attr = indexAttrIter.next();

        String attrName = attr.getName();
        if ("id".equals(attrName)) {
          continue;
        }

        Object attrValue = value.get(attrName);
        Object transformed = (attrValue == null) ? null : transformAttributeValue(attrValue, attr);

        String attrValueString =
            (transformed != null) ? URLEncoder.encode(transformed.toString(), "UTF-8") : "$";

        theKey.append(attrValueString);

        if (indexAttrIter.hasNext()) {
          theKey.append("|");
        }
      }
    } catch (Exception shouldntHappen) {
      throw new RuntimeException(shouldntHappen);
    }

    return theKey.toString();
  }

  public Map<String, List<QueryTerm>> sortTerms(IndexDefinition indexDef, List<QueryTerm> terms)
      throws KazukiException {
    Map<String, List<QueryTerm>> termMap = new LinkedHashMap<String, List<QueryTerm>>();

    Set<String> termFields = new HashSet<String>();

    for (QueryTerm term : terms) {
      String attrName = term.getField();
      termFields.add(attrName);

      if (!indexDef.getAttributeNames().contains(attrName)) {
        throw new KazukiException("'" + attrName + "' not in index");
      }
    }

    for (IndexAttribute attribute : indexDef.getIndexAttributes()) {
      String attrName = attribute.getName();

      if (indexDef.isUnique() && !attrName.equals("id") && !termFields.contains(attrName)) {
        throw new KazukiException("unique index query must specify all fields");
      }

      for (QueryTerm term : terms) {
        if (term.getField().equals(attrName)) {
          List<QueryTerm> attrTerms = termMap.get(attrName);
          if (attrTerms == null) {
            attrTerms = new ArrayList<QueryTerm>();
            termMap.put(attrName, attrTerms);
          }

          attrTerms.add(term);
        }
      }
    }

    return termMap;
  }

  private String bindParam(IndexAttribute attribute, Schema schema, FieldTransform transform,
      SqlParamBindings bindings, int param, String attrName, ValueHolder value)
      throws KazukiException {
    if (!value.getValueType().equals(ValueType.NULL)) {
      Object instance = value.getValue();
      Object transformed = transform.transformValue(attrName, instance);

      if (transformed != null) {
        transformed = transformed.toString();
      }

      if (attrName.equals("id")) {
        try {
          transformed = KeyImpl.valueOf(transformed.toString());
        } catch (Exception e) {
          throw new KazukiException("invalid id: '" + instance.toString() + "'");
        }
      }

      return bindings.bind(
          "p" + param,
          transformAttributeValue(transformed, attribute),
          "id".equals(attribute.getName()) ? Attribute.Type.U64 : schema.getAttributeMap()
              .get(attribute.getName()).getType());
    }

    return null;
  }

  private String getUniqueIndexIdentifier(String type, String index) throws Exception {
    return String.format("%04d", sequences.getTypeId(type, false)) + "__" + getIndexHexId(index);
  }
}