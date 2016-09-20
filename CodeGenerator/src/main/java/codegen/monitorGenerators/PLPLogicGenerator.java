package codegen.monitorGenerators;

import codegen.common.PythonWriter;
import codegen.middlewareGenerators.MiddlewareGenerator;
import conditions.*;
import distributions.ConditionalDist;
import effects.AssignmentEffect;
import effects.ConditionalEffect;
import effects.Effect;
import modules.*;
import plpEtc.Predicate;
import plpFields.*;
import sun.applet.Main;

import java.util.*;

public class PLPLogicGenerator {

    public static Map<Condition,String> conditionMethods;

    // TODO: maintain plp - create methods to check time until true (if initially false)
    public static String GeneratePLPModule(PLP plp) {
        conditionMethods = new HashMap<>();
        PythonWriter generator = new PythonWriter();

        generator.writeLine("from PLPClasses import *");
        generator.writeLine(String.format("from PLP%sClasses import *",plp.getBaseName()));
        generator.newLine();
        generator.writeLine("# TODO update this variable to the max variable history needed");
        generator.writeLine(String.format("PLP_%s_HISTORY_LENGTH = 2",plp.getBaseName()));
        generator.newLine();


        generator.writeLine(String.format("class PLP%s(object):",plp.getBaseName()));
        generator.newLine();
        generator.indent();
        generator.writeLine("def __init__(self, constant_map, parameters, callback):");
        generator.newLine();
        generator.indent();
        if (plp.getClass().isAssignableFrom(MaintainPLP.class)) {
            generator.writeLine("self.maintained_condition_true = " + (((MaintainPLP) plp).isInitiallyTrue() ? "True" : "False"));
        }
        generator.dendent();
        generator.dendent();
        generator.writeFileContent(PLPLogicGenerator.class.getResource("/PLPModuleHead.txt").getPath());
        generator.newLine();
        generator.indent();

        generator.writeIndentedBlock(generateCanEstimate(plp, generator.getCurrentTabLevel()));
        generator.newLine();
        generator.writeLine("# The following methods are used to check the observable conditions of the PLP.");
        generator.writeLine("# Access parameters using: self.parameters of type PLP"
                + plp.getBaseName() + "Parameters");
        generator.writeLine("# Access variables using: self.variables() of type PLP"
                + plp.getBaseName() + "Variables");
        generator.writeLine("# Access variable history using: self.variables_history[index]");
        generator.write("# Access constants using: self.constants[constant_name]");
        generateAllConditionCheckers(generator, plp, true);

        // validate preconditions
        generator.newLine();
        generator.writeLine("def validate_preconditions(self):");
        generator.indent();
        if (plp.getPreConditions().size() > 1) {
            BitwiseOperation preConditions = new BitwiseOperation(BitwiseOperation.Operation.AND, plp.getPreConditions());
            generator.writeLine("return "+generateIFcondition(preConditions));
        }
        else if (plp.getPreConditions().size() == 1) {
            generator.writeLine("return " + generateIFcondition(plp.getPreConditions().get(0)));
        }
        generator.dendent();
        //

        // estimations
        generator.newLine();
        generator.writeIndentedBlock(generateEstimationFunctions(plp,generator.getCurrentTabLevel()));
        //

        // monitor termination
        generateTerminationDetectors(generator, plp, true);
        //

        // concurrency conditions
        generator.newLine();
        generator.writeLine("def monitor_conditions(self):");
        generator.indent();
        for (Condition c : plp.getConcurrencyConditions()) {
            generator.writeLine("if not "+generateIFcondition(c)+":");
            generator.indent();
            generator.writeLine("self.callback.plp_monitor_message(PLPMonitorMessage(\"" + c.toString() + "\", False, \"Concurrency condition doesn't hold\"))");
            generator.dendent();
        }
        /*if (plp.getConcurrencyConditions().size() > 1) {
            BitwiseOperation concurrencyConditions = new BitwiseOperation(BitwiseOperation.Operation.AND, plp.getConcurrencyConditions());
            generator.writeLine("return " + generateIFcondition(concurrencyConditions));
        }
        else if (plp.getConcurrencyConditions().size() == 1) {
            generator.writeLine("return " + generateIFcondition(plp.getConcurrencyConditions().get(0)));
        }*/
        generator.dendent();
        generator.newLine();
        //

        // Maintain: maintained condition
        if (plp.getClass().isAssignableFrom(MaintainPLP.class)) {
            generator.newLine();
            generator.writeLine("def monitor_maintained_condition(self):");
            generator.indent();
            generator.writeLine("if not self.maintained_condition_true:");
            generator.indent();
            generator.writeLine("if " + generateIFcondition(((MaintainPLP) plp).getMaintainedCondition()) + ":");
            generator.indent();
            generator.writeLine("self.maintained_condition_true = True");
            generator.dendent();
            generator.dendent();
            generator.writeLine("else:");
            generator.indent();
            generator.writeLine("if not " + generateIFcondition(((MaintainPLP) plp).getMaintainedCondition()) + ":");
            generator.indent();
            generator.writeLine("self.callback.plp_monitor_message(PLPMonitorMessage(\"" + ((MaintainPLP) plp).getMaintainedCondition().toString() + "\", False, \"Maintain condition doesn't hold\"))");
            generator.dendent();
            generator.dendent();
            generator.dendent();
            generator.newLine();
        }
        //

        //progress measures
        for (ProgressMeasure pm : plp.getProgressMeasures()) {
            generator.writeLine(String.format("def monitor_progress_%s(self):",pm.getCondition().simpleString()));
            generator.indent();
            if (pm.getCondition().getClass().isAssignableFrom(BitwiseOperation.class) &&
                    ((BitwiseOperation) pm.getCondition()).getOperation().equals(BitwiseOperation.Operation.AND)) {
                for (Condition c : ((BitwiseOperation) pm.getCondition()).getConditions()) {
                    generator.writeLine("if not " + generateIFcondition(c) + ":");
                    generator.indent();
                    generator.writeLine("self.callback.plp_monitor_message(PLPMonitorMessage(\"" + c.toString() + "\", False, \"Progress measure doesn't hold\"))");
                    generator.dendent();
                }
            }
            else {
                generator.writeLine("if not " + generateIFcondition(pm.getCondition()) + ":");
                generator.indent();
                generator.writeLine("self.callback.plp_monitor_message(PLPMonitorMessage(\"" + pm.getCondition().toString() + "\", False, \"Progress measure doesn't hold\"))");
                generator.dendent();
            }
            generator.dendent();
            generator.newLine();
        }
        //

        // variables
        generateVariablesFunctions(plp, generator, true);
        generator.newLine();
        //

        // parameters updated callback
        generator.writeLine("def parameters_updated(self):");
        generator.indent();
        generator.writeLine("# Called when parameters where updated (might effect variables)");
        generator.writeLine("# Triggers estimation and monitoring. You can uncomment one if you're not interested in it");
        generator.writeLine("termination = self.detect_termination()");
        generator.writeLine("if termination is None:");
        generator.indent();
        generator.writeLine("self.request_estimation()");
        generator.writeLine("self.monitor_conditions()");
        if (plp.getClass().isAssignableFrom(MaintainPLP.class)) {
            generator.writeLine("self.monitor_maintained_condition()");
        }
        generator.dendent();
        generator.writeLine("else:");
        generator.indent();
        generator.writeLine("self.callback.plp_terminated(termination)");
        generator.dendent();
        generator.dendent();
        //

        generator.setIndent(0);
        return generator.end();
    }

    public static void generateVariablesFunctions(PLP plp, PythonWriter generator, boolean includeHistory) {

        generator.writeLine("def calculate_variables(self):");
        generator.indent();

        if (includeHistory) {
            generator.writeLine(String.format("variables = PLP_%s_variables()",plp.getBaseName()));
            for (Variable var : plp.getVariables()) {
                generator.writeLine(String.format("variables.%1$s = self.calc_%1$s()",var.getName()));
            }

            generator.writeLine(String.format("if len(self.variables_history) >= PLP_%s_HISTORY_LENGTH:", plp.getBaseName()));
            generator.indent();
            generator.writeLine("self.variables_history = [variables] + self.variables_history[0:-1]");
            generator.dendent();
            generator.writeLine("else:");
            generator.indent();
            generator.writeLine("self.variables_history = [variables] + self.variables_history");
            generator.dendent();
            generator.dendent();

            generator.newLine();
            generator.writeLine("def variables(self):");
            generator.indent();
            generator.writeLine("# The newest variables");
            generator.writeLine("return self.variables_history[0]");
        }
        else {
            for (Variable var : plp.getVariables()) {
                generator.writeLine(String.format("self.plp_vars.%1$s = self.calc_%1$s()",var.getName()));
            }
        }

        generator.dendent();

        generator.newLine();
        generator.writeLine("# The following methods are used to update the variables");
        generator.writeLine("# Access parameters using: self.parameters of type PLP_"
                + plp.getBaseName() + "_parameters");
        generator.writeLine("# Access constants using: self.constants[constant_name]");

        generator.newLine();
        for (Variable var : plp.getVariables()) {
            generator.writeLine(String.format("def calc_%s(self):",var.getName()));
            generator.indent();
            generator.writeLine("# TODO Implement code to calculate "+var.getName());
            generator.writeLine("# return the value of the variable ");
            generator.writeLine("return None");
            generator.dendent();
            generator.newLine();
        }

    }

    public static void generateTerminationDetectors(PythonWriter generator, PLP plp, boolean isMonitor) {
        // Success detection ---------------------------------------------------------
        generator.writeLine("def detect_success(self):");
        generator.indent();
        if (plp.getClass().isAssignableFrom(AchievePLP.class)) {
            AchievePLP aplp = (AchievePLP) plp;
            generator.writeLine("if "+generateIFcondition(aplp.getGoal())+":");
            generator.indent();
            generator.writeLine(isMonitor ? "return PLPTermination(True, \" Achieved: " +aplp.getGoal().toString()
                    + "\")" : "return True");
            generator.dendent();
            if (aplp.hasSuccessTerminationCond()) {
                generator.writeLine("elif "+generateIFcondition(aplp.getSuccessTerminationCond())+":");
                generator.indent();
                generator.writeLine(isMonitor ? "return PLPTermination(True, \" Achieved: "
                        + aplp.getGoal().toString() + "\")" : "return True");
                generator.dendent();
            }
            generator.writeLine("else:");
            generator.indent();
            generator.writeLine("return None");
            generator.dendent();
        }
        else if (plp.getClass().isAssignableFrom(MaintainPLP.class)) {
            MaintainPLP mplp = (MaintainPLP) plp;
            generator.writeLine("if "+generateIFcondition(mplp.getSuccessTerminationCondition())+":");
            generator.indent();
            generator.writeLine(isMonitor ? "return PLPTermination(True, \" Maintained: "
                    + mplp.getSuccessTerminationCondition().toString() + "\")" : "return True");
            generator.dendent();
            generator.writeLine("else:");
            generator.indent();
            generator.writeLine("return None");
            generator.dendent();
        }
        else if (plp.getClass().isAssignableFrom(ObservePLP.class)) {
            ObservePLP oplp = (ObservePLP) plp;
            if (oplp.isGoalParameter()) {
                generator.writeLine(String.format("if self.plp_params.%s is not None:",
                        ((PLPParameter) oplp.getGoal()).simpleString()));
                generator.indent();
                generator.writeLine("# TODO: Optionally, add more conditions on the returned value, to determine if the observation finished successfully");
            }
            else {
                if (oplp.getResultParameter() == null)
                    throw new RuntimeException("Observe PLP: " + oplp.getBaseName() + " doesn't have a result parameter");
                generator.writeLine(String.format("if self.plp_params.%s is not None" +
                        (MiddlewareGenerator.domainType == MiddlewareGenerator.DomainType.PARTIALLY_OBSERVABLE
                                ? " and self.sense_contradiction is False:" : ":"),
                        oplp.getResultParameter().simpleString()));
                generator.indent();
            }
            generator.writeLine(isMonitor ? "return PLPTermination(True, \" Observed: "
                    + oplp.getGoal() + "\")" : "return True");
            generator.dendent();
            generator.writeLine("return None");
        }
        else if (plp.getClass().isAssignableFrom(DetectPLP.class)) {
            DetectPLP dplp = (DetectPLP) plp;
            if (dplp.getResultParameter() == null)
                throw new RuntimeException("Detect PLP: " + dplp.getBaseName() + " doesn't have a result parameter");
            generator.writeLine(String.format("if self.plp_params.%s is not None:",
                    dplp.getResultParameter().simpleString()));
            generator.indent();
            generator.writeLine("# TODO: Optionally, add more conditions on the returned value, to determine if the detection finished successfully");
            generator.writeLine(isMonitor ? "return PLPTermination(True, \" Detected: "
                    + dplp.getGoal() + "\")" : "return True");
            generator.dendent();
            generator.writeLine("return None");
        }
        else {
            throw new RuntimeException("Unsupported PLP type");
        }
        generator.dendent();
        generator.newLine();

        // Failure detection ---------------------------------------------------------
        generator.writeLine("def detect_failures(self):");
        generator.indent();
        if (plp.getClass().isAssignableFrom(AchievePLP.class)) {
            AchievePLP aplp = (AchievePLP) plp;
            for (FailureMode fm : aplp.getFailureModes()) {
                Condition failCond = fm.getCondition();
                generator.writeLine("if " + generateIFcondition(failCond) + ":");
                generator.indent();
                if (!isMonitor && MiddlewareGenerator.domainType == MiddlewareGenerator.DomainType.PARTIALLY_OBSERVABLE) {
                    generator.writeLine("self.update_assumptions_fail()");
                    generator.newLine();
                }
                generator.writeLine(isMonitor ? "return PLPTermination(False, \" Failed by condition: " + failCond.toString()
                        + "\")" : "return True");
                generator.dendent();
            }
            if (aplp.hasFailTerminationCond()) {
                generator.writeLine("if "+generateIFcondition(aplp.getFailTerminationCond())+":");
                generator.indent();
                if (!isMonitor && MiddlewareGenerator.domainType == MiddlewareGenerator.DomainType.PARTIALLY_OBSERVABLE) {
                    generator.writeLine("self.update_assumptions_fail()");
                    generator.newLine();
                }
                generator.writeLine(isMonitor ? "return PLPTermination(False, \" Failed by condition: "
                        + aplp.getFailTerminationCond().toString() + "\")" : "return True");
                generator.dendent();
            }
            generator.writeLine("return None");
        }
        else if (plp.getClass().isAssignableFrom(MaintainPLP.class)) {
            MaintainPLP mplp = (MaintainPLP) plp;
            List<Condition> failureConditions = mplp.getFailureTerminationConditions();
            for (Condition c: failureConditions) {
                generator.writeLine("if " + generateIFcondition(c) + ":");
                generator.indent();
                if (!isMonitor && MiddlewareGenerator.domainType == MiddlewareGenerator.DomainType.PARTIALLY_OBSERVABLE) {
                    generator.writeLine("self.update_assumptions_fail()");
                    generator.newLine();
                }
                generator.writeLine(isMonitor ? "return PLPTermination(False, \" Failed by condition: " + c.toString()
                        + "\")" : "return True");
                generator.dendent();
            }
            for (FailureMode fm : mplp.getFailureModes()) {
                Condition failCond = fm.getCondition();
                generator.writeLine("if " + generateIFcondition(failCond) + ":");
                generator.indent();
                if (!isMonitor && MiddlewareGenerator.domainType == MiddlewareGenerator.DomainType.PARTIALLY_OBSERVABLE) {
                    generator.writeLine("self.update_assumptions_fail()");
                    generator.newLine();
                }
                generator.writeLine(isMonitor ? "return PLPTermination(False, \" Failed by condition: " + failCond.toString()
                        + "\")" : "return True");
                generator.dendent();
            }
            generator.writeLine("return None");
        }
        else if (plp.getClass().isAssignableFrom(ObservePLP.class)) {
            ObservePLP oplp = (ObservePLP) plp;
            if (!isMonitor && !oplp.isGoalParameter() &&
                MiddlewareGenerator.domainType == MiddlewareGenerator.DomainType.PARTIALLY_OBSERVABLE) {
                generator.writeLine("if self.sense_contradiction:");
                generator.indent();
                generator.writeLine("return True");
                generator.dendent();
            }
            if (oplp.hasFailTerminationCond()) {
                generator.writeLine("if "+generateIFcondition(oplp.getFailTerminationCond())+":");
                generator.indent();
                if (!isMonitor && MiddlewareGenerator.domainType == MiddlewareGenerator.DomainType.PARTIALLY_OBSERVABLE) {
                    generator.writeLine("self.update_assumptions_fail()");
                    generator.newLine();
                }
                generator.writeLine(isMonitor ? "return PLPTermination(False, \" Failed by condition: "
                        + oplp.getFailTerminationCond().toString() + "\")" : "return True");
                generator.dendent();
            }
            else {
                generator.writeLine("# TODO: Implement failure to observe condition. No failed termination conditions specified");
                if (!isMonitor && MiddlewareGenerator.domainType == MiddlewareGenerator.DomainType.PARTIALLY_OBSERVABLE) {
                    generator.writeLine("# And call self.update_assumptions_fail() on failure");
                }
            }
            generator.writeLine("return None");
        }
        else if (plp.getClass().isAssignableFrom(DetectPLP.class)) {
            DetectPLP dplp = (DetectPLP) plp;
            if (dplp.hasFailTerminationCond()) {
                generator.writeLine("if "+generateIFcondition(dplp.getFailTerminationCond())+":");
                generator.indent();
                if (!isMonitor && MiddlewareGenerator.domainType == MiddlewareGenerator.DomainType.PARTIALLY_OBSERVABLE) {
                    generator.writeLine("self.update_assumptions_fail()");
                    generator.newLine();
                }
                generator.writeLine(isMonitor ? "return PLPTermination(False, \" Failed by condition: "
                        + dplp.getFailTerminationCond().toString() + "\")" : "return True");
                generator.dendent();
            }
            else {
                generator.writeLine("# TODO: Implement failure to detect. No failed termination conditions specified");
                if (!isMonitor && MiddlewareGenerator.domainType == MiddlewareGenerator.DomainType.PARTIALLY_OBSERVABLE) {
                    generator.writeLine("# And call self.update_assumptions_fail() on failure");
                }
            }
            generator.writeLine("return None");
        }
        else {
            throw new RuntimeException("Unsupported PLP type");
        }
        generator.dendent();
    }

    private static String generateEstimationFunctions(PLP plp, int currentTabLevel) {
        PythonWriter generator = new PythonWriter(currentTabLevel);

        if (AchievePLP.class.isInstance(plp)) {
            AchievePLP aplp = (AchievePLP) plp;
            generator.writeLine("def estimate(self):");
            generator.indent();
            generator.writeLine("result = PlpAchieveResult()");
            generator.writeLine("result.success = self.estimate_success()");
            generator.writeLine("result.success_time = self.estimate_success_time()");

            for (Effect sEff : plp.getSideEffects()) {
                generator.writeLine(String.format("result.side_effects[\"%1$s\"] = self.estimate_%1$s_side_effect()",
                        sEff.simpleString()));
            }

            for (FailureMode fm : aplp.getFailureModes()) {
                generator.writeLine(String.format("result.add_failure(self.estimate_%s_failure())",
                        fm.getCondition().simpleString()));
            }
            generator.writeLine("return result");
            generator.dendent();
            generator.newLine();

            generator.writeLine("def estimate_success(self):");
            generator.indent();
            generator.writeLine("result = \"\"");
            for (ConditionalProb cProb : aplp.getSuccessProb()) {
                if (cProb.isConditional()) {
                    generateEstimateProbability(cProb, generator);
                }
                else {
                    generator.writeLine("# TODO Implement the code that computes and returns the following probability");
                    generator.writeLine("# probability = " + cProb.getProb().toString());
                    generator.writeLine("result = \"to_implement\"");
                }
            }
            generator.writeLine("return result");
            generator.dendent();
            generator.newLine();

            generator.writeLine("def estimate_success_time(self):");
            generator.indent();
            generator.writeLine("result = \"\"");
            for (ConditionalDist cDist : aplp.getSuccessRuntime()) {
                if (cDist.isConditional()) {
                    generateEstimateTime(cDist, generator);
                }
                else {
                    generator.writeLine("# TODO Implement the code that computes and returns the following distribution");
                    generator.writeLine("# distribution = " + cDist.getDist().toString());
                    generator.writeLine("result = \"to_implement\"");
                }
            }
            generator.writeLine("return result");
            generator.dendent();
            generator.newLine();

            for (Effect sEff : plp.getSideEffects()) {
                generator.writeLine(String.format("def estimate_%s_side_effect(self):",sEff.simpleString()));
                generator.indent();
                generator.writeLine("result = \"\"");
                if (ConditionalEffect.class.isInstance(sEff)) {
                    generateEstimateSideEffect(((ConditionalEffect) sEff), generator);
                }
                else
                {
                    if (AssignmentEffect.class.isInstance(sEff)) {
                        generator.writeLine("#TODO Implement the code that computes the parameters new value \"val\" to be the following:");
                        generator.writeLine("# new value = " + ((AssignmentEffect) sEff).getExpression());
                        generator.writeLine("val = to_implement");
                        generator.writeLine("result += \"" +
                                ((AssignmentEffect) sEff).getParam() + "  = \" + val + \"\\n\"");
                    }
                    else {
                        generator.writeLine("result += \"" +
                                sEff.toString() + "\" + \"\\n\"");
                    }
                }
                generator.writeLine("return result");
            }
            generator.dendent();
            generator.newLine();

            for (FailureMode fm : aplp.getFailureModes()) {
                generator.writeLine(String.format("def estimate_%s_failure()",
                        fm.getCondition().simpleString()));
                generator.indent();
                generator.writeLine("result = \"\"");
                for (ConditionalProb cProb : fm.getProbList()) {
                    if (cProb.isConditional()) {
                        generateEstimateProbability(cProb, generator);
                    }
                    else {
                        generator.writeLine("# TODO Implement the code that computes and returns the following probability");
                        generator.writeLine("# probability = " + cProb.getProb().toString());
                        generator.writeLine("result = \"to_implement\"");
                    }
                }
                generator.writeLine("return result");
                generator.dendent();
                generator.newLine();
            }
        }
        else {
            generator.writeLine("# TODO estimation functions for this PLP type"); // TODO
        }
        generator.setIndent(0);
        return generator.end();
    }

    private static void generateEstimateSideEffect(ConditionalEffect condEff, PythonWriter generator) {
        if (Predicate.class.isInstance(condEff.getCondition())
                || Formula.class.isInstance(condEff.getCondition())
                || QuantifiedCondition.class.isInstance(condEff.getCondition())) {
            if (!conditionMethods.get(condEff.getCondition()).equals("uncomputable")) {
                generator.writeLine("if " + conditionMethods.get(condEff.getCondition()) + ":");
                generator.indent();
                if (AssignmentEffect.class.isInstance(condEff.getEffect())) {
                    generator.writeLine("# TODO Implement the code that computes the parameters new value");
                    generator.writeLine("# new_value = " + ((AssignmentEffect) condEff.getEffect()).getExpression());
                    generator.writeLine("result = \""+((AssignmentEffect) condEff.getEffect()).getParam()
                                            + " = \" + new_value ");
                }
                else {
                    generator.writeLine("result = \""+condEff.getEffect().toString()+"\"");
                }
                generator.newLine();
            }
            else {
                if (AssignmentEffect.class.isInstance(condEff.getEffect())) {
                    generator.writeLine("#TODO Implement the code that computes the parameters new value \"val\" to be the following:");
                    generator.writeLine("# new value = " + ((AssignmentEffect) condEff.getEffect()).getExpression());
                    generator.writeLine("val = to_implement");
                    generator.writeLine("result += \"If "+condEff.getCondition().toString()+" : " +
                            ((AssignmentEffect) condEff.getEffect()).getParam() + "\"  = val + \"\\n\"");
                    generator.newLine();
                }
                else {
                    generator.writeLine("result += \"If "+condEff.getCondition().toString()+" : " +
                            condEff.getEffect().toString() + "\" + \"\\n\"");
                }
            }
        }
        else if (NotCondition.class.isInstance(condEff.getCondition())) {
            generator.writeLine("if "+conditionMethods.get(((NotCondition) condEff.getCondition()).getCondition())+":");
            generator.indent();
            if (AssignmentEffect.class.isInstance(condEff.getEffect())) {
                generator.writeLine("#TODO Implement the code that computes the parameters new value \"val\" to be the following:");
                generator.writeLine("# new value = " + ((AssignmentEffect) condEff.getEffect()).getExpression());
                generator.writeLine("val = to_implement");
                generator.writeLine("result += \"If " + condEff.getCondition().toString() + " : " +
                        ((AssignmentEffect) condEff.getEffect()).getParam() + "\"  = val + \"\\n\"");
                generator.newLine();
            }
            else {
                generator.writeLine("result += \"If "+condEff.getCondition().toString()+" : " +
                        condEff.getEffect().toString() + "\" + \"\\n\"");
            }
        }
        else if (BitwiseOperation.class.isInstance(condEff.getCondition())) {
            BitwiseOperation bCond = (BitwiseOperation) condEff.getCondition();
            generator.writeLine("if "+generateIFcondition(bCond)+":");
            generator.indent();
            if (AssignmentEffect.class.isInstance(condEff.getEffect())) {
                generator.writeLine("#TODO Implement the code that computes the parameters new value \"val\" to be the following:");
                generator.writeLine("# new value = " + ((AssignmentEffect) condEff.getEffect()).getExpression());
                generator.writeLine("val = to_implement");
                generator.writeLine("result += \"If " + condEff.getCondition().toString() + " : " +
                        ((AssignmentEffect) condEff.getEffect()).getParam() + "\"  = val + \"\\n\"");
                generator.newLine();
            }
            else {
                generator.writeLine("result += \"If "+condEff.getCondition().toString()+" : " +
                        condEff.getEffect().toString() + "\" + \"\\n\"");
            }
        }
    }

    private static void generateEstimateTime(ConditionalDist cDist, PythonWriter generator) {
        if (Predicate.class.isInstance(cDist.getCondition())
                || Formula.class.isInstance(cDist.getCondition())
                || QuantifiedCondition.class.isInstance(cDist.getCondition())) {
            if (!conditionMethods.get(cDist.getCondition()).equals("uncomputable")) {
                generator.writeLine("if "+conditionMethods.get(cDist.getCondition())+":");
                generator.indent();
                generator.writeLine("# TODO Implement the code that computes and returns the following distribution");
                generator.writeLine("# distribution = " + cDist.getDist().toString());
                generator.writeLine("result = \"to_implement\"");
                generator.newLine();
            }
            else {
                generator.writeLine("# TODO Implement the code that computes \"dist\" to be the following distribution");
                generator.writeLine("# distribution = " + cDist.getDist().toString());
                generator.writeLine("dist = to_implement");
                generator.writeLine("result += \"If "+cDist.getCondition().toString()+" : \" + prob + \"\\n\"");
                generator.newLine();
            }
        }
        else if (NotCondition.class.isInstance(cDist.getCondition())) {
            generator.writeLine("if "+conditionMethods.get(((NotCondition) cDist.getCondition()).getCondition())+":");
            generator.indent();
            generator.writeLine("# TODO Implement the code that computes and returns the following distribution");
            generator.writeLine("# distribution = " + cDist.getDist().toString());
            generator.writeLine("result = \"to_implement\"");
            generator.newLine();
        }
        else if (BitwiseOperation.class.isInstance(cDist.getCondition())) {
            BitwiseOperation bCond = (BitwiseOperation) cDist.getCondition();
            generator.writeLine("if "+generateIFcondition(bCond)+":");
            generator.indent();
            generator.writeLine("# TODO Implement the code that computes and returns the following distribution");
            generator.writeLine("# distribution = " + cDist.getDist().toString());
            generator.writeLine("result = \"to_implement\"");
            generator.newLine();
        }
    }

    private static void generateEstimateProbability(ConditionalProb cProb, PythonWriter generator) {
        if (Predicate.class.isInstance(cProb.getCondition())
                || Formula.class.isInstance(cProb.getCondition())
                || QuantifiedCondition.class.isInstance(cProb.getCondition())) {
            if (!conditionMethods.get(cProb.getCondition()).equals("uncomputable")) {
                generator.writeLine("if "+conditionMethods.get(cProb.getCondition())+":");
                generator.indent();
                generator.writeLine("# TODO Implement the code that computes and returns the following probability");
                generator.writeLine("# probability = " + cProb.getProb());
                generator.writeLine("result = \"to_implement\"");
                generator.newLine();
            }
            else {
                generator.writeLine("# TODO Implement the code that computes \"prob\" to be the following probability");
                generator.writeLine("# probability = " + cProb.getProb());
                generator.writeLine("prob = to_implement");
                generator.writeLine("result += \"If "+cProb.getCondition().toString()+" : \" + prob + \"\\n\"");
                generator.newLine();
            }
        }
        else if (NotCondition.class.isInstance(cProb.getCondition())) {
            generator.writeLine("if "+conditionMethods.get(((NotCondition) cProb.getCondition()).getCondition())+":");
            generator.indent();
            generator.writeLine("# TODO Implement the code that computes and returns the following probability");
            generator.writeLine("# probability = " + cProb.getProb());
            generator.writeLine("result = \"to_implement\"");
            generator.newLine();
        }
        else if (BitwiseOperation.class.isInstance(cProb.getCondition())) {
            BitwiseOperation bCond = (BitwiseOperation) cProb.getCondition();
            generator.writeLine("if "+generateIFcondition(bCond)+":");
            generator.indent();
            generator.writeLine("# TODO Implement the code that computes and returns the following probability");
            generator.writeLine("# probability = " + cProb.getProb());
            generator.writeLine("result = \"to_implement\"");
            generator.newLine();
        }
    }


    public static void generateAllConditionCheckers(PythonWriter generator, PLP plp, boolean isMonitor) {
        generator.newLine();

        loadBaseConditionsFromPLP(plp, isMonitor);
        for (Map.Entry<Condition,String> entry : conditionMethods.entrySet()) {
            if (!entry.getValue().equals("uncomputable")) {
                generator.newLine();
                generator.writeLine("def " + entry.getValue() + ":");
                generator.indent();
                if (Predicate.class.isInstance(entry.getKey())) {
                    generator.writeLine("# TODO implement code that checks the following predicate condition");
                    generator.writeLine("# Predicate: " + entry.getKey().toString());
                } else if (Formula.class.isInstance(entry.getKey())) {
                    generator.writeLine("# TODO implement code that checks the following formula");
                    generator.writeLine("# Formula: " + entry.getKey().toString());
                } else if (QuantifiedCondition.class.isInstance(entry.getKey())) {
                    QuantifiedCondition qCond = (QuantifiedCondition) entry.getKey();
                    generateQuantifiedCode(generator, qCond);
                } else
                    generator.writeLine("# Unpredictable condition checker function generated");
                generator.writeLine("return False");
                generator.dendent();
            }
        }
    }

    private static void generateQuantifiedCode(PythonWriter generator, QuantifiedCondition qCond) {
        generator.writeLine("# TODO implement code that checks the following "+qCond.getQuantifier()+" condition");
        generator.writeLine("# Condition: " + qCond.toString());
        generator.writeLine("# You can use the following template:");
        generator.writeLine("'''");
        if (qCond.getQuantifier() == QuantifiedCondition.Quantifier.EXISTS) {
            generator.writeLine("result = False");
            generator.writeLine("for each "+ Arrays.toString(qCond.getParams().toArray()));
            generator.indent();
            generator.writeLine("if "+generateIFcondition(qCond.getCondition())+":");
            generator.indent();
            generator.writeLine("result = True");
        }
        else {
            generator.writeLine("result = True");
            generator.writeLine("for each "+ Arrays.toString(qCond.getParams().toArray()));
            generator.indent();
            generator.writeLine("if not "+generateIFcondition(qCond.getCondition())+":");
            generator.indent();
            generator.writeLine("result = False");
        }
        generator.dendent();
        generator.dendent();
        generator.writeLine("return result");
        generator.writeLine("'''");
    }

    private static String generateIFcondition(Condition condition) {
        if (Predicate.class.isInstance(condition)
                || Formula.class.isInstance(condition)
                || QuantifiedCondition.class.isInstance(condition)) {
            if (conditionMethods.get(condition).equals("uncomputable"))
                return "(False)";
            return "(self." + conditionMethods.get(condition).replaceAll("self","") + ")";
        }
        else if (NotCondition.class.isInstance(condition)) {
            return "(not " + generateIFcondition(((NotCondition) condition).getCondition()) + ")";
        }
        else if (BitwiseOperation.class.isInstance(condition)) {
            BitwiseOperation bOP = (BitwiseOperation) condition;
            StringBuilder bitwiseSB = new StringBuilder();
            bitwiseSB.append("(");
            String op = bOP.getOperation().toString().toLowerCase();
            for (Condition c : bOP.getConditions()) {
                bitwiseSB.append(generateIFcondition(c)).append(" ").append(op).append(" ");
            }
            bitwiseSB.delete(bitwiseSB.length()-2-op.length(),bitwiseSB.length());
            bitwiseSB.append(")");
            return bitwiseSB.toString();
        }
        else
            return "(Unpredictable IF statement to generate)";
    }

    /**
     * This method loads the conditions of the given PLP for further method generation.
     * The monitoring generation covers every condition in the PLP.
     * The ROSPlan middleware generation covers only the conditions relevant for detecting termination.
     * @param plp The PLP whose base conditions are loaded
     * @param isMonitor True if this method is called to generate monitoring scripts. False if ROSPlan middleware scripts.
     */
    public static void loadBaseConditionsFromPLP(PLP plp, boolean isMonitor) {
        if (isMonitor) {
            for (Condition precond : plp.getPreConditions()) {
                addBaseConditionToMap(plp, precond);
            }
            for (Condition concond : plp.getConcurrencyConditions()) {
                addBaseConditionToMap(plp, concond);
            }
            for (Effect sEff : plp.getSideEffects()) {
                if (ConditionalEffect.class.isInstance(sEff) &&
                        ((ConditionalEffect) sEff).isConditional()) {
                    addBaseConditionToMap(plp, ((ConditionalEffect) sEff).getCondition());
                }
            }
            for (ProgressMeasure pm : plp.getProgressMeasures()) {
                addBaseConditionToMap(plp, pm.getCondition());
            }
        }

        // SPECIFIC CONDITIONS FOR ACHIEVE
        if (AchievePLP.class.isInstance(plp)) {
            AchievePLP aplp = (AchievePLP) plp;

            addBaseConditionToMap(plp, aplp.getGoal());

            if (aplp.hasSuccessTerminationCond()) addBaseConditionToMap(plp, aplp.getSuccessTerminationCond());
            if (aplp.hasFailTerminationCond()) addBaseConditionToMap(plp, aplp.getFailTerminationCond());

            for (FailureMode fm : aplp.getFailureModes()) {
                addBaseConditionToMap(plp, fm.getCondition());
                if (isMonitor) {
                    for (ConditionalProb cProb : fm.getProbList()) {
                        if (cProb.isConditional())
                            addBaseConditionToMap(plp, cProb.getCondition());
                    }
                }
            }

            if (isMonitor) {
                for (ConditionalProb cProb : aplp.getSuccessProb()) {
                    if (cProb.isConditional())
                        addBaseConditionToMap(plp, cProb.getCondition());
                }
                for (ConditionalProb cProb : aplp.getGeneralFailureProb()) {
                    if (cProb.isConditional())
                        addBaseConditionToMap(plp, cProb.getCondition());
                }
                for (ConditionalDist cDist : aplp.getSuccessRuntime()) {
                    if (cDist.isConditional())
                        addBaseConditionToMap(plp, cDist.getCondition());
                }
                for (ConditionalDist cDist : aplp.getFailRuntime()) {
                    if (cDist.isConditional())
                        addBaseConditionToMap(plp, cDist.getCondition());
                }
            }
        }
        // SPECIFIC CONDITIONS FOR MAINTAIN
        else if (MaintainPLP.class.isInstance(plp)) {
            MaintainPLP mplp = (MaintainPLP) plp;

            addBaseConditionToMap(plp, mplp.getSuccessTerminationCondition());
            for (Condition c : mplp.getFailureTerminationConditions()) {
                addBaseConditionToMap(plp, c);
            }

            for (FailureMode fm : mplp.getFailureModes()) {
                addBaseConditionToMap(plp, fm.getCondition());
                if (isMonitor) {
                    for (ConditionalProb cProb : fm.getProbList()) {
                        if (cProb.isConditional())
                            addBaseConditionToMap(plp, cProb.getCondition());
                    }
                }
            }
            if (isMonitor) {
                addBaseConditionToMap(plp, mplp.getMaintainedCondition());
                for (ConditionalProb cProb : mplp.getSuccessProb()) {
                    if (cProb.isConditional())
                        addBaseConditionToMap(plp, cProb.getCondition());
                }

                for (ConditionalProb cProb : mplp.getGeneralFailureProb()) {
                    if (cProb.isConditional())
                        addBaseConditionToMap(plp, cProb.getCondition());
                }
                for (ConditionalDist cDist : mplp.getSuccessRuntime()) {
                    if (cDist.isConditional())
                        addBaseConditionToMap(plp, cDist.getCondition());
                }
                for (ConditionalDist cDist : mplp.getFailRuntime()) {
                    if (cDist.isConditional())
                        addBaseConditionToMap(plp, cDist.getCondition());
                }
                if (mplp.hasTimeUntilTrue()) {
                    for (ConditionalDist cDist : mplp.getTimeUntilTrue()) {
                        if (cDist.isConditional())
                            addBaseConditionToMap(plp, cDist.getCondition());
                    }
                }
            }
        }
        // SPECIFIC CONDITIONS FOR OBSERVE
        else if (ObservePLP.class.isInstance(plp)) {
            ObservePLP oplp = (ObservePLP) plp;

            if (oplp.hasFailTerminationCond()) addBaseConditionToMap(plp, oplp.getFailTerminationCond());

            if (isMonitor) {
                /*if (!oplp.isGoalParameter()) {
                    addBaseConditionToMap(plp, (Condition) oplp.getGoal());
                }*/
                for (ConditionalProb cProb : oplp.getFailureToObserveProb()) {
                    if (cProb.isConditional())
                        addBaseConditionToMap(plp, cProb.getCondition());
                }
                for (ConditionalProb cProb : oplp.getCorrectObservationProb()) {
                    if (cProb.isConditional())
                        addBaseConditionToMap(plp, cProb.getCondition());
                }
                for (ConditionalDist cDist : oplp.getSuccessRuntime()) {
                    if (cDist.isConditional())
                        addBaseConditionToMap(plp, cDist.getCondition());
                }
                for (ConditionalDist cDist : oplp.getFailureRuntime()) {
                    if (cDist.isConditional())
                        addBaseConditionToMap(plp, cDist.getCondition());
                }
            }
        }
        // SPECIFIC CONDITIONS FOR DETECT
        else if (DetectPLP.class.isInstance(plp)) {
            DetectPLP dplp = (DetectPLP) plp;

            if (dplp.hasFailTerminationCond()) addBaseConditionToMap(plp, dplp.getFailTerminationCond());

            if (isMonitor) {
                for (ConditionalProb cProb : dplp.getSuccessProbGivenCondition()) {
                    if (cProb.isConditional())
                        addBaseConditionToMap(plp, cProb.getCondition());
                }
            }
        }
    }

    private static void addBaseConditionToMap(PLP plp, Condition cond) {
        addBaseConditionToMap(plp,cond,"self");
    }

    private static void addBaseConditionToMap(PLP plp, Condition cond, String signatureArgs) {
        if (cond.getClass().isAssignableFrom(BitwiseOperation.class)) {
            for (Condition c : ((BitwiseOperation) cond).getConditions()) {
                addBaseConditionToMap(plp, c);
            }
        }
        else if (cond.getClass().isAssignableFrom(NotCondition.class)) {
            addBaseConditionToMap(plp, ((NotCondition) cond).getCondition());
        }
        else if (cond.getClass().isAssignableFrom(QuantifiedCondition.class)) {
            if (!conditionMethods.containsKey(cond)) {
                String params = Arrays.toString(((QuantifiedCondition) cond).getParams().toArray());
                params = params.substring(1,params.length()-1).replace(",","");
                addBaseConditionToMap(plp, ((QuantifiedCondition) cond).getCondition(), signatureArgs+ "," +params);
                if (uncomputableCondition(plp, cond))
                    conditionMethods.put(cond, "uncomputable");
                else
                    conditionMethods.put(cond, "check_"+cond.simpleString()+"("+signatureArgs+")");
            }
        }
        else if (cond.getClass().isAssignableFrom(Formula.class)) {
            if (!conditionMethods.containsKey(cond)) {
                if (uncomputableCondition(plp, cond))
                    conditionMethods.put(cond, "uncomputable");
                else
                    conditionMethods.put(cond,
                            "check_condition_".concat(((Formula) cond).getKeyDesc())+"("+signatureArgs+")");
            }
        }
        else if (cond.getClass().isAssignableFrom(Predicate.class)) {
            if (!conditionMethods.containsKey(cond)) {
                if (uncomputableCondition(plp, cond))
                    conditionMethods.put(cond, "uncomputable");
                else
                    conditionMethods.put(cond,
                            "check_condition_".concat(((Predicate) cond).simpleString())+"("+signatureArgs+")");
            }
        }
    }

    private static boolean uncomputableCondition(PLP plp, Condition cond) {
        for (PLPParameter param : plp.getUnobservableParams()) {
            if (cond.containsParam(param.getName()))
                return true;
        }
        return false;
    }

    private static String generateCanEstimate(PLP plp, int currentTabLevel) {
        PythonWriter generator = new PythonWriter(currentTabLevel);
        generator.writeLine("def can_estimate(self):");
        //generator.setIndent(currentTabLevel);

        generator.indent();

        StringBuilder paramsCheck = new StringBuilder();
        paramsCheck.append("return not (");
        for (PLPParameter param : plp.getExecParams()) {
            paramsCheck.append(String.format("(self.parameters.%s is None) or ",param.simpleString()));
        }
        for (PLPParameter param : plp.getInputParams()) {
            paramsCheck.append(String.format("(self.parameters.%s is None) or ",param.simpleString()));
        }
        // TODO: check if no params
        paramsCheck.delete(paramsCheck.length()-4,paramsCheck.length());
        paramsCheck.append(")");

        generator.writeLine(paramsCheck.toString());
        generator.setIndent(0);
        return generator.end();
    }





}
