package ghidra.app.plugin.prototype.CppCodeAnalyzerPlugin.gcc;

import ghidra.app.cmd.data.rtti.ClassTypeInfo;
import ghidra.app.cmd.data.rtti.GnuVtable;
import ghidra.app.cmd.data.rtti.Vtable;
import ghidra.app.cmd.data.rtti.gcc.VtableUtils;
import ghidra.app.cmd.data.rtti.gcc.VttModel;
import ghidra.app.cmd.function.CreateThunkFunctionCmd;
import ghidra.framework.cmd.BackgroundCommand;
import ghidra.framework.model.DomainObject;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.util.Msg;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

import cppclassanalyzer.utils.CppClassAnalyzerUtils;

import static ghidra.app.cmd.data.rtti.gcc.ClassTypeInfoUtils.getClassFunction;

public class GccVtableAnalysisCmd extends BackgroundCommand {

	private static final String NAME = GccVtableAnalysisCmd.class.getSimpleName();

	private ClassTypeInfo typeinfo;
	private Program program;
	private TaskMonitor monitor;

	protected GccVtableAnalysisCmd() {
		super(NAME, false, true, false);
	}

	public GccVtableAnalysisCmd(ClassTypeInfo type) {
		this();
		this.typeinfo = type;
	}

	public void setTypeInfo(ClassTypeInfo type) {
		this.typeinfo = type;
	}

	@Override
	public boolean applyTo(DomainObject obj, TaskMonitor monitor) {
		if (!(obj instanceof Program)) {
			String message = "Can only analyze a vtable in a program.";
			Msg.error(this, message);
			return false;
		}
		this.program = (Program) obj;
		this.monitor = monitor;
		try {
			Vtable vtable = typeinfo.getVtable();
			VttModel vtt = null;
			if (Vtable.isValid(vtable)) {
				vtt = VtableUtils.getVttModel(program, (GnuVtable) vtable);
			}
			if (vtt != null && vtt.isValid()) {
				for (Vtable parentVtable : vtt.getConstructionVtableModels()) {
					monitor.checkCanceled();
					setupFunctions(parentVtable);
				}
			}
			setupFunctions(vtable);
		} catch (CancelledException e) {
		} catch (Exception e) {
			setStatusMsg(e.getMessage());
			return false;
		}
		return true;
	}

	private void setupFunctions(Vtable vftable) throws Exception {
		ClassTypeInfo type = vftable.getTypeInfo();
		Function[][] functionTables = vftable.getFunctionTables();
		// Also if the function has a reference to this::vtable, then it owns the function
		for (int i = 0; i < functionTables.length; i++) {
			monitor.checkCanceled();
			if (i == 0) {
				for (Function function : functionTables[i]) {
					monitor.checkCanceled();
					if (!CppClassAnalyzerUtils.isDefaultFunction(function)) {
						continue;
					} getClassFunction(program, type, function.getEntryPoint());
				}
			} else {
				setupThunkFunctions(type, vftable, functionTables[i], i);
			}
		}
	}

	private void setupThunkFunctions(ClassTypeInfo type, Vtable vftable,
		Function[] functionTable, int ordinal) throws CancelledException {
		for (Function function : functionTable) {
			monitor.checkCanceled();
			if (!CppClassAnalyzerUtils.isDefaultFunction(function)) {
				continue;
			}
			if (CreateThunkFunctionCmd.isThunk(program, function)) {
				CreateThunkFunctionCmd cmd =
					new CreateThunkFunctionCmd(function.getEntryPoint(), false);
				cmd.applyTo(program, monitor);
			} else {
				getClassFunction(program, type, function.getEntryPoint());
			}
		}
	}
}