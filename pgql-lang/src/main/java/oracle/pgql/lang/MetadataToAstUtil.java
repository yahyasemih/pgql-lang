package oracle.pgql.lang;

import static oracle.pgql.lang.CommonTranslationUtil.getString;
import static oracle.pgql.lang.CommonTranslationUtil.isSome;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.metaborg.spoofax.core.unit.ISpoofaxAnalyzeUnit;
import org.metaborg.spoofax.core.unit.ISpoofaxParseUnit;
import org.spoofax.interpreter.terms.IStrategoAppl;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.spoofax.interpreter.terms.ITermFactory;
import org.spoofax.interpreter.terms.TermType;
import org.spoofax.terms.TermVisitor;

import oracle.pgql.lang.ir.SchemaQualifiedName;
import oracle.pgql.lang.metadata.AbstractMetadataProvider;
import oracle.pgql.lang.metadata.EdgeLabel;
import oracle.pgql.lang.metadata.GraphSchema;
import oracle.pgql.lang.metadata.Label;
import oracle.pgql.lang.metadata.Property;
import oracle.pgql.lang.metadata.VertexLabel;

public class MetadataToAstUtil {

  private static final int POS_AST_PLUS_METADATA_AST_EXPRESSIONS = 0;

  private static final String AST_PLUS_METADATA_CONSTRUCTOR_NAME = "AstPlusMetadata";

  static ISpoofaxParseUnit addMetadata(ISpoofaxParseUnit parseResult, AbstractMetadataProvider metadataProvider,
      ITermFactory f) {
    if (!((IStrategoAppl) parseResult.ast()).getConstructor().getName().equals("Query")) {
      // for DDL statements and other non-query statement, we don't add metadata
      return parseResult;
    }

    if (metadataProvider == null) {
      return parseResult;
    }

    Set<SchemaQualifiedName> graphNames = extractGraphNames(parseResult.ast());
    Optional<GraphSchema> graphSchema;
    if (graphNames.size() > 1) {
      // multiple graph references in single query are currently not supported
      // we already generate an error for that during analysis so we can just return here
      return parseResult;
    } else if (graphNames.size() == 1) {
      SchemaQualifiedName graphName = graphNames.iterator().next();
      graphSchema = metadataProvider.getGraphSchema(graphName);
    } else {
      graphSchema = metadataProvider.getGraphSchema();
    }

    List<IStrategoTerm> metadataTerm = new ArrayList<>();
    if (graphSchema.isPresent()) {

      List<IStrategoTerm> vertexLabelTerms = new ArrayList<>();
      for (VertexLabel vertexLabel : graphSchema.get().getVertexLabels()) {
        vertexLabelTerms.add(translateLabel(vertexLabel, f));
      }
      IStrategoAppl vertexLabelsTerm = f.makeAppl("VertexLabels", f.makeList(vertexLabelTerms));
      metadataTerm.add(vertexLabelsTerm);

      List<IStrategoTerm> edgeLabelTerms = new ArrayList<>();
      for (EdgeLabel edgeLabel : graphSchema.get().getEdgeLabels()) {
        edgeLabelTerms.add(translateLabel(edgeLabel, f));
      }
      IStrategoAppl edgeLabelsTerm = f.makeAppl("EdgeLabels", f.makeList(edgeLabelTerms));
      metadataTerm.add(edgeLabelsTerm);
    }

    IStrategoAppl metadataExtendedAst = f.makeAppl(AST_PLUS_METADATA_CONSTRUCTOR_NAME, parseResult.ast(),
        f.makeList(metadataTerm));
    System.out.println(metadataTerm);
    ISpoofaxParseUnit extendedParseUnit = new ModifiedParseUnit(parseResult, metadataExtendedAst);
    return extendedParseUnit;
  }

  static IStrategoTerm translateLabel(Label label, ITermFactory f) {
    List<IStrategoTerm> propertyTerms = new ArrayList<>();
    for (Property property : label.getProperties()) {
      propertyTerms.add(f.makeAppl("Property", f.makeString(property.getName()), f.makeString(property.getType())));
    }

    return f.makeAppl("Label", f.makeString(label.getLabel()), f.makeList(propertyTerms));
  }

  static IStrategoTerm removeMetadata(ISpoofaxAnalyzeUnit analysisResult) {
    IStrategoTerm analyizedAst;
    if (((IStrategoAppl) analysisResult.ast()).getConstructor().getName().equals(AST_PLUS_METADATA_CONSTRUCTOR_NAME)) {
      analyizedAst = analysisResult.ast().getSubterm(POS_AST_PLUS_METADATA_AST_EXPRESSIONS);
    } else {
      analyizedAst = analysisResult.ast();
    }
    return analyizedAst;
  }

  static Set<SchemaQualifiedName> extractGraphNames(IStrategoTerm ast) {

    final Set<SchemaQualifiedName> graphNames = new HashSet<>();

    new TermVisitor() {

      @Override
      public void preVisit(IStrategoTerm t) {
        if (t.getType() == TermType.APPL && ((IStrategoAppl) t).getConstructor().getName().equals("OnClause")) {
          IStrategoTerm nameT = t.getSubterm(0);
          IStrategoTerm schemaNameT = nameT.getSubterm(0);
          String schemaName = isSome(schemaNameT) ? identifierToString(schemaNameT.getSubterm(0).getSubterm(0)) : null;
          String localName = identifierToString(nameT.getSubterm(1));
          graphNames.add(new SchemaQualifiedName(schemaName, localName));
        }
      }

    }.visit(ast);

    return graphNames;
  }

  static String identifierToString(IStrategoTerm t) {
    String constructorName = ((IStrategoAppl) t).getConstructor().getName();
    String identifier = getString(t);
    switch (constructorName) {
      case "RegularIdentifier":
        return identifier.toUpperCase();
      case "DelimitedIdentifier":
        return identifier.substring(1, identifier.length() - 1).replaceAll("\"\"", "\"");
      default:
        throw new IllegalStateException("Unsupported identifier type: " + constructorName);
    }
  }
}
