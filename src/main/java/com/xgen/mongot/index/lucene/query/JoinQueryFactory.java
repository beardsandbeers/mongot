package com.xgen.mongot.index.lucene.query;

import com.xgen.mongot.index.lucene.field.FieldName;
import com.xgen.mongot.index.query.operators.MaterializedJoinOperator;
import java.util.Optional;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.util.BytesRef;

/** Builds exact membership from already normalized token values. */
final class JoinQueryFactory {
  static Query fromJoin(MaterializedJoinOperator operator) {
    if (operator.keys().isEmpty()) {
      return new MatchNoDocsQuery("No foreign join keys");
    }
    String field =
        FieldName.TypeField.TOKEN.getLuceneFieldName(
            operator.join().localField(), Optional.empty());
    return new TermInSetQuery(field, operator.keys().stream().map(BytesRef::new).toList());
  }
}
