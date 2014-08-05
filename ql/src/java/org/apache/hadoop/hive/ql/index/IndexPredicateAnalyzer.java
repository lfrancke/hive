/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.ql.index;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import org.apache.hadoop.hive.ql.exec.FunctionRegistry;
import org.apache.hadoop.hive.ql.lib.DefaultGraphWalker;
import org.apache.hadoop.hive.ql.lib.DefaultRuleDispatcher;
import org.apache.hadoop.hive.ql.lib.Dispatcher;
import org.apache.hadoop.hive.ql.lib.GraphWalker;
import org.apache.hadoop.hive.ql.lib.Node;
import org.apache.hadoop.hive.ql.lib.NodeProcessor;
import org.apache.hadoop.hive.ql.lib.NodeProcessorCtx;
import org.apache.hadoop.hive.ql.lib.Rule;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.ql.plan.ExprNodeColumnDesc;
import org.apache.hadoop.hive.ql.plan.ExprNodeConstantDesc;
import org.apache.hadoop.hive.ql.plan.ExprNodeDesc;
import org.apache.hadoop.hive.ql.plan.ExprNodeDescUtils;
import org.apache.hadoop.hive.ql.plan.ExprNodeFieldDesc;
import org.apache.hadoop.hive.ql.plan.ExprNodeGenericFuncDesc;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDF;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDFBridge;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoFactory;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDFBaseCompare;

/**
 * IndexPredicateAnalyzer decomposes predicates, separating the parts
 * which can be satisfied by an index from the parts which cannot.
 * Currently, it only supports pure conjunctions over binary expressions
 * comparing a column reference with a constant value.  It is assumed
 * that all column aliases encountered refer to the same table.
 */
public class IndexPredicateAnalyzer {

  private final Set<String> udfNames;
  private final Set<String> allowedColumnNames;
  private FieldValidator fieldValidator;

  private boolean acceptsFields;

  public IndexPredicateAnalyzer() {
    udfNames = new HashSet<String>();
    allowedColumnNames = new HashSet<String>();
  }

  public void setFieldValidator(FieldValidator fieldValidator) {
    this.fieldValidator = fieldValidator;
  }

  /**
   * Registers a comparison operator as one which can be satisfied
   * by an index search.  Unless this is called, analyzePredicate
   * will never find any indexable conditions.
   *
   * @param udfName name of comparison operator as returned
   * by either {@link GenericUDFBridge#getUdfName} (for simple UDF's)
   * or udf.getClass().getName() (for generic UDF's).
   */
  public void addComparisonOp(String udfName) {
    udfNames.add(udfName);
  }

  /**
   * Clears the set of column names allowed in comparisons.  (Initially, all
   * column names are allowed.)
   */
  public void clearAllowedColumnNames() {
    allowedColumnNames.clear();
  }

  /**
   * Adds a column name to the set of column names allowed.
   *
   * @param columnName name of column to be allowed
   */
  public void allowColumnName(String columnName) {
    allowedColumnNames.add(columnName);
  }

  /**
   * Analyzes a predicate.
   *
   * @param predicate predicate to be analyzed
   *
   * @param searchConditions receives conditions produced by analysis
   *
   * @return residual predicate which could not be translated to
   * searchConditions
   */
  public ExprNodeDesc analyzePredicate(
    ExprNodeDesc predicate,
    final List<IndexSearchCondition> searchConditions) {

    Map<Rule, NodeProcessor> opRules = new LinkedHashMap<Rule, NodeProcessor>();
    NodeProcessor nodeProcessor = new NodeProcessor() {
      @Override
      public Object process(Node nd, Stack<Node> stack,
        NodeProcessorCtx procCtx, Object... nodeOutputs)
        throws SemanticException {

        // We can only push down stuff which appears as part of
        // a pure conjunction:  reject OR, CASE, etc.
        for (Node ancestor : stack) {
          if (nd == ancestor) {
            break;
          }
          if (!FunctionRegistry.isOpAnd((ExprNodeDesc) ancestor)) {
            return nd;
          }
        }

        return analyzeExpr((ExprNodeGenericFuncDesc) nd, searchConditions, nodeOutputs);
      }
    };

    Dispatcher disp = new DefaultRuleDispatcher(
      nodeProcessor, opRules, null);
    GraphWalker ogw = new DefaultGraphWalker(disp);
    ArrayList<Node> topNodes = new ArrayList<Node>();
    topNodes.add(predicate);
    HashMap<Node, Object> nodeOutput = new HashMap<Node, Object>();
    try {
      ogw.startWalking(topNodes, nodeOutput);
    } catch (SemanticException ex) {
      throw new RuntimeException(ex);
    }
    ExprNodeDesc residualPredicate = (ExprNodeDesc) nodeOutput.get(predicate);
    return residualPredicate;
  }

  private ExprNodeDesc analyzeExpr(
    ExprNodeGenericFuncDesc expr,
    List<IndexSearchCondition> searchConditions,
    Object... nodeOutputs) {

    if (FunctionRegistry.isOpAnd(expr)) {
      assert(nodeOutputs.length == 2);
      ExprNodeDesc residual1 = (ExprNodeDesc) nodeOutputs[0];
      ExprNodeDesc residual2 = (ExprNodeDesc) nodeOutputs[1];
      if (residual1 == null) {
        return residual2;
      }
      if (residual2 == null) {
        return residual1;
      }
      List<ExprNodeDesc> residuals = new ArrayList<ExprNodeDesc>();
      residuals.add(residual1);
      residuals.add(residual2);
      return new ExprNodeGenericFuncDesc(
        TypeInfoFactory.booleanTypeInfo,
        FunctionRegistry.getGenericUDFForAnd(),
        residuals);
    }

    GenericUDF genericUDF = expr.getGenericUDF();
    if (!(genericUDF instanceof GenericUDFBaseCompare)) {
      return expr;
    }
    ExprNodeDesc expr1 = (ExprNodeDesc) nodeOutputs[0];
    ExprNodeDesc expr2 = (ExprNodeDesc) nodeOutputs[1];
    ExprNodeDesc[] extracted = ExprNodeDescUtils.extractComparePair(expr1, expr2);
    if (extracted == null || (extracted.length > 2 && !acceptsFields)) {
      return expr;
    }

    ExprNodeColumnDesc columnDesc;
    ExprNodeConstantDesc constantDesc;
    if (extracted[0] instanceof ExprNodeConstantDesc) {
      genericUDF = genericUDF.flip();
      columnDesc = (ExprNodeColumnDesc) extracted[1];
      constantDesc = (ExprNodeConstantDesc) extracted[0];
    } else {
      columnDesc = (ExprNodeColumnDesc) extracted[0];
      constantDesc = (ExprNodeConstantDesc) extracted[1];
    }

    String udfName = genericUDF.getUdfName();
    if (!udfNames.contains(genericUDF.getUdfName())) {
      return expr;
    }

    if (!allowedColumnNames.contains(columnDesc.getColumn())) {
      return expr;
    }

    String[] fields = null;
    if (extracted.length > 2) {
      ExprNodeFieldDesc fieldDesc = (ExprNodeFieldDesc) extracted[2];
      if (!isValidField(fieldDesc)) {
        return expr;
      }
      fields = ExprNodeDescUtils.extractFields(fieldDesc);
    }

    searchConditions.add(
      new IndexSearchCondition(
        columnDesc,
        udfName,
        constantDesc,
        expr,
        fields));

    // we converted the expression to a search condition, so
    // remove it from the residual predicate
    return fields == null ? null : expr;
  }

  private boolean isValidField(ExprNodeFieldDesc field) {
    return fieldValidator == null || fieldValidator.validate(field);
  }

  /**
   * Translates search conditions back to ExprNodeDesc form (as
   * a left-deep conjunction).
   *
   * @param searchConditions (typically produced by analyzePredicate)
   *
   * @return ExprNodeGenericFuncDesc form of search conditions
   */
  public ExprNodeGenericFuncDesc translateSearchConditions(
    List<IndexSearchCondition> searchConditions) {

    ExprNodeGenericFuncDesc expr = null;
    for (IndexSearchCondition searchCondition : searchConditions) {
      if (expr == null) {
        expr = searchCondition.getComparisonExpr();
        continue;
      }
      List<ExprNodeDesc> children = new ArrayList<ExprNodeDesc>();
      children.add(expr);
      children.add(searchCondition.getComparisonExpr());
      expr = new ExprNodeGenericFuncDesc(
        TypeInfoFactory.booleanTypeInfo,
        FunctionRegistry.getGenericUDFForAnd(),
        children);
    }
    return expr;
  }

  public void setAcceptsFields(boolean acceptsFields) {
    this.acceptsFields = acceptsFields;
  }

  public interface FieldValidator {
    boolean validate(ExprNodeFieldDesc exprNodeDesc);
  }

  public static IndexPredicateAnalyzer createAnalyzer(boolean equalOnly) {

    IndexPredicateAnalyzer analyzer = new IndexPredicateAnalyzer();
    analyzer.addComparisonOp("org.apache.hadoop.hive.ql.udf.generic.GenericUDFOPEqual");
    if (equalOnly) {
      return analyzer;
    }
    analyzer.addComparisonOp("org.apache.hadoop.hive.ql.udf.generic.GenericUDFOPEqualOrGreaterThan");
    analyzer.addComparisonOp("org.apache.hadoop.hive.ql.udf.generic.GenericUDFOPEqualOrLessThan");
    analyzer.addComparisonOp("org.apache.hadoop.hive.ql.udf.generic.GenericUDFOPLessThan");
    analyzer.addComparisonOp("org.apache.hadoop.hive.ql.udf.generic.GenericUDFOPGreaterThan");

    return analyzer;
  }
}
