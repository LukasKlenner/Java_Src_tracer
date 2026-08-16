package srctracer;

import com.github.javaparser.ast.body.MethodDeclaration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static srctracer.KeyAnnotater.JAVA_SOURCE_DIR;
import static srctracer.util.JavaParserUtil.getParamDescriptor;
import static srctracer.util.JavaParserUtil.getQualifiedClassName;

public class KeyProofObligationCreator {

    public static void createProofObligation(
            Path outputDir,
            MethodDeclaration tracedMethod,
            Path traceFile,
            Path functionDatabaseFile
    ) throws IOException {

        if (!traceFile.startsWith(outputDir)) {
            throw new IllegalArgumentException("Trace file must be inside the output directory");
        }

        if (!functionDatabaseFile.startsWith(outputDir)) {
            throw new IllegalArgumentException("Function database file must be inside the output directory");
        }

        String qualifiedClass = getQualifiedClassName(tracedMethod);
        String paramDescriptor = getParamDescriptor(tracedMethod);
        String methodName = tracedMethod.getNameAsString();

        String contract = String.format(
                "%s[%s::%s(%s)].JML normal_behavior operation contract.0",
                qualifiedClass, qualifiedClass, methodName, paramDescriptor);

        String keyFile = String.format(PROOF_OBLIGATION_TEMPLATE,
                JAVA_SOURCE_DIR,
                outputDir.relativize(traceFile),
                outputDir.relativize(functionDatabaseFile),
                contract,
                contract
        );


        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("proof.key"), keyFile);
    }


    /**
     * changed:
     * - added tracing: on
     * - method expansion: no_restriction
     */
    private static final String PROOF_OBLIGATION_TEMPLATE = """
            \\profile "Java Profile with Tracing";
            
             \\settings // Proof-Settings-Config-File
             {
                 "Choice" : {
                     "JavaCard" : "JavaCard:off",
                     "Strings" : "Strings:on",
                     "assertions" : "assertions:safe",
                     "bigint" : "bigint:on",
                     "floatRules" : "floatRules:strictfpOnly",
                     "initialisation" : "initialisation:disableStaticInitialisation",
                     "intRules" : "intRules:arithmeticSemanticsIgnoringOF",
                     "integerSimplificationRules" : "integerSimplificationRules:full",
                     "javaLoopTreatment" : "javaLoopTreatment:efficient",
                     "mergeGenerateIsWeakeningGoal" : "mergeGenerateIsWeakeningGoal:off",
                     "methodExpansion" : "methodExpansion:noRestriction",
                     "modelFields" : "modelFields:treatAsAxiom",
                     "moreSeqRules" : "moreSeqRules:on",
                     "permissions" : "permissions:off",
                     "programRules" : "programRules:Java",
                     "reach" : "reach:on",
                     "runtimeExceptions" : "runtimeExceptions:ban",
                     "sequences" : "sequences:on",
                     "wdChecks" : "wdChecks:off",
                     "wdOperator" : "wdOperator:L",
                     "tracing": "tracing:on"
                  },
                 "Labels" : {
                     "UseOriginLabels" : true
                  },
                 "NewSMT" : {
            
                  },
                 "SMTSettings" : {
                     "SelectedTaclets" : [
            
                      ],
                     "UseBuiltUniqueness" : false,
                     "explicitTypeHierarchy" : false,
                     "instantiateHierarchyAssumptions" : true,
                     "integersMaximum" : 2147483645,
                     "integersMinimum" : -2147483645,
                     "invariantForall" : false,
                     "maxGenericSorts" : 2,
                     "useConstantsForBigOrSmallIntegers" : true,
                     "useUninterpretedMultiplication" : true
                  },
                 "Strategy" : {
                     "ActiveStrategy" : "JavaCardDLStrategy",
                     "MaximumNumberOfAutomaticApplications" : 100000,
                     "Timeout" : -1,
                     "options" : {
                         "AUTO_INDUCTION_OPTIONS_KEY" : "AUTO_INDUCTION_OFF",
                         "BLOCK_OPTIONS_KEY" : "BLOCK_EXPAND",
                         "CLASS_AXIOM_OPTIONS_KEY" : "CLASS_AXIOM_FREE",
                         "DEP_OPTIONS_KEY" : "DEP_ON",
                         "INF_FLOW_CHECK_PROPERTY" : "INF_FLOW_CHECK_FALSE",
                         "LOOP_OPTIONS_KEY" : "LOOP_EXPAND",
                         "METHOD_OPTIONS_KEY" : "METHOD_EXPAND",
                         "MPS_OPTIONS_KEY" : "MPS_MERGE",
                         "NON_LIN_ARITH_OPTIONS_KEY" : "NON_LIN_ARITH_DEF_OPS",
                         "OSS_OPTIONS_KEY" : "OSS_ON",
                         "QUANTIFIERS_OPTIONS_KEY" : "QUANTIFIERS_NON_SPLITTING_WITH_PROGS",
                         "QUERYAXIOM_OPTIONS_KEY" : "QUERYAXIOM_ON",
                         "QUERY_NEW_OPTIONS_KEY" : "QUERY_OFF",
                         "SPLITTING_OPTIONS_KEY" : "SPLITTING_DELAYED",
                         "STOPMODE_OPTIONS_KEY" : "STOPMODE_DEFAULT",
                         "SYMBOLIC_EXECUTION_ALIAS_CHECK_OPTIONS_KEY" : "SYMBOLIC_EXECUTION_ALIAS_CHECK_NEVER",
                         "SYMBOLIC_EXECUTION_NON_EXECUTION_BRANCH_HIDING_OPTIONS_KEY" : "SYMBOLIC_EXECUTION_NON_EXECUTION_BRANCH_HIDING_OFF",
                         "USER_TACLETS_OPTIONS_KEY1" : "USER_TACLETS_OFF",
                         "USER_TACLETS_OPTIONS_KEY2" : "USER_TACLETS_OFF",
                         "USER_TACLETS_OPTIONS_KEY3" : "USER_TACLETS_OFF",
                         "VBT_PHASE" : "VBT_SYM_EX"
                      }
                  }
              }
            
             \\javaSource "%s";
            
              \\traceFile "%s";
              \\traceFunctionDB "%s";
            
             \\proofObligation
             // Proof-Obligation settings
             {
                 "class" : "de.uka.ilkd.key.proof.init.FunctionalOperationContractPO",
                 "contract" : "%s",
                 "name" : "%s"
              }""";
}
