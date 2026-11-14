package com.xgen.mongot.index.query.operators;

import com.xgen.mongot.index.query.scores.Score;
import java.util.List;
import org.bson.BsonValue;

/** Internal immutable indexed-token membership. Keys are never serialized into a request. */
public record MaterializedJoinOperator(JoinOperator join, List<String> keys) implements Operator {
  public MaterializedJoinOperator(JoinOperator join, List<String> keys) {
    this.join = join;
    this.keys = List.copyOf(keys);
  }

  @Override
  public Type getType() {
    return Type.JOIN;
  }

  @Override
  public Score score() {
    return Score.defaultScore();
  }

  @Override
  public BsonValue operatorToBson() {
    return this.join.operatorToBson();
  }
}
