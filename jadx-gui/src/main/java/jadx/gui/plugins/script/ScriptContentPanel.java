package jadx.gui.plugins.script;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.KeyEvent;
import java.util.Collections;
import java.util.List;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.border.EmptyBorder;

import org.fife.ui.rsyntaxtextarea.ErrorStrip;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pinterest.ktlint.core.LintError;

import kotlin.script.experimental.api.ScriptDiagnostic;

import jadx.gui.settings.JadxSettings;
import jadx.gui.settings.LineNumbersMode;
import jadx.gui.treemodel.JInputScript;
import jadx.gui.ui.MainWindow;
import jadx.gui.ui.TabbedPane;
import jadx.gui.ui.codearea.AbstractCodeArea;
import jadx.gui.ui.codearea.AbstractCodeContentPanel;
import jadx.gui.ui.codearea.SearchBar;
import jadx.gui.utils.Icons;
import jadx.gui.utils.NLS;
import jadx.gui.utils.UiUtils;
import jadx.gui.utils.ui.ActionHandler;
import jadx.gui.utils.ui.NodeLabel;
import jadx.plugins.script.ide.ScriptAnalyzeResult;
import jadx.plugins.script.ide.ScriptCompiler;

public class ScriptContentPanel extends AbstractCodeContentPanel {
	private static final long serialVersionUID = 6575696321112417513L;

	private static final Logger LOG = LoggerFactory.getLogger(ScriptContentPanel.class);

	private final ScriptCodeArea scriptArea;
	private final SearchBar searchBar;
	private final RTextScrollPane codeScrollPane;
	private final JPanel actionPanel;
	private final JLabel resultLabel;
	private final ScriptErrorService errorService;

	public ScriptContentPanel(TabbedPane panel, JInputScript scriptNode) {
		super(panel, scriptNode);
		scriptArea = new ScriptCodeArea(this, scriptNode);
		resultLabel = new NodeLabel("");
		errorService = new ScriptErrorService(scriptArea);
		actionPanel = buildScriptActionsPanel();
		searchBar = new SearchBar(scriptArea);
		codeScrollPane = new RTextScrollPane(scriptArea);

		initUI();
		applySettings();
		scriptArea.load();
	}

	private void initUI() {
		JPanel topPanel = new JPanel(new BorderLayout());
		topPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
		topPanel.add(actionPanel, BorderLayout.NORTH);
		topPanel.add(searchBar, BorderLayout.SOUTH);

		JPanel codePanel = new JPanel(new BorderLayout());
		codePanel.setBorder(new EmptyBorder(0, 0, 0, 0));
		codePanel.add(codeScrollPane);
		codePanel.add(new ErrorStrip(scriptArea), BorderLayout.LINE_END);

		setLayout(new BorderLayout());
		setBorder(new EmptyBorder(0, 0, 0, 0));
		add(topPanel, BorderLayout.NORTH);
		add(codeScrollPane, BorderLayout.CENTER);

		KeyStroke key = KeyStroke.getKeyStroke(KeyEvent.VK_F, UiUtils.ctrlButton());
		UiUtils.addKeyBinding(scriptArea, key, "SearchAction", searchBar::toggle);
	}

	private JPanel buildScriptActionsPanel() {
		ActionHandler runAction = new ActionHandler(this::runScript);
		runAction.setNameAndDesc(NLS.str("script.run"));
		runAction.setIcon(Icons.RUN);
		runAction.attachKeyBindingFor(scriptArea, KeyStroke.getKeyStroke(KeyEvent.VK_F8, 0));

		ActionHandler saveAction = new ActionHandler(scriptArea::save);
		saveAction.setNameAndDesc(NLS.str("script.save"));
		saveAction.setIcon(Icons.SAVE_ALL);
		saveAction.setKeyBinding(KeyStroke.getKeyStroke(KeyEvent.VK_S, UiUtils.ctrlButton()));

		JButton save = saveAction.makeButton();
		scriptArea.getScriptNode().addChangeListener(save::setEnabled);

		JButton check = new JButton(NLS.str("script.check"), Icons.CHECK);
		check.addActionListener(ev -> checkScript());
		JButton format = new JButton(NLS.str("script.format"), Icons.FORMAT);
		format.addActionListener(ev -> reformatCode());

		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.LINE_AXIS));
		panel.setBorder(new EmptyBorder(0, 0, 0, 0));
		panel.add(runAction.makeButton());
		panel.add(Box.createRigidArea(new Dimension(10, 0)));
		panel.add(save);
		panel.add(Box.createRigidArea(new Dimension(10, 0)));
		panel.add(check);
		panel.add(Box.createRigidArea(new Dimension(10, 0)));
		panel.add(format);
		panel.add(Box.createRigidArea(new Dimension(30, 0)));
		panel.add(resultLabel);
		panel.add(Box.createHorizontalGlue());
		return panel;
	}

	private void runScript() {
		scriptArea.save();
		if (!checkScript()) {
			return;
		}
		resetResultLabel();

		TabbedPane tabbedPane = getTabbedPane();
		MainWindow mainWindow = tabbedPane.getMainWindow();
		mainWindow.getBackgroundExecutor().execute(NLS.str("script.run"), () -> {
			try {
				mainWindow.getWrapper().getDecompiler().reloadPasses();
			} catch (Exception e) {
				LOG.error("Passes reload failed", e);
			}
		}, taskStatus -> {
			tabbedPane.reloadInactiveTabs();
			mainWindow.reloadTree();
		});
	}

	private boolean checkScript() {
		try {
			resetResultLabel();
			String code = scriptArea.getText();
			String fileName = scriptArea.getNode().getName();

			ScriptCompiler scriptCompiler = new ScriptCompiler(fileName);
			ScriptAnalyzeResult result = scriptCompiler.analyze(code, scriptArea.getCaretPosition());
			List<ScriptDiagnostic> errors = result.getErrors();
			for (ScriptDiagnostic error : errors) {
				LOG.warn("Parse error: {}", error);
			}

			List<LintError> lintErrs = Collections.emptyList();
			if (errors.isEmpty()) {
				lintErrs = getLintIssues(code, fileName);
			}

			errorService.clearErrors();
			errorService.addErrors(errors);
			errorService.addLintErrors(lintErrs);
			errorService.apply();
			boolean success = errors.isEmpty();
			if (!success) {
				resultLabel.setText("Parsing errors: " + errors.size());
			} else if (!lintErrs.isEmpty()) {
				resultLabel.setText("Lint issues: " + lintErrs.size());
			}
			return success;
		} catch (Throwable e) {
			LOG.error("Failed to check code", e);
			return true;
		}
	}

	private List<LintError> getLintIssues(String code, String fileName) {
		try {
			List<LintError> lintErrs = KtLintUtils.INSTANCE.lint(code, fileName);
			for (LintError error : lintErrs) {
				LOG.warn("Lint issue: {}", error);
			}
			return lintErrs;
		} catch (Throwable e) { // can throw initialization error
			LOG.warn("KtLint failed", e);
			return Collections.emptyList();
		}
	}

	private void reformatCode() {
		resetResultLabel();
		try {
			String code = scriptArea.getText();
			String fileName = scriptArea.getNode().getName();
			String formattedCode = KtLintUtils.INSTANCE.format(code, fileName);
			if (!code.equals(formattedCode)) {
				scriptArea.updateCode(formattedCode);
				resultLabel.setText("Code updated");
				errorService.clearErrors();
			}
		} catch (Throwable e) { // can throw initialization error
			LOG.error("Failed to reformat code", e);
		}
	}

	private void resetResultLabel() {
		resultLabel.setText("");
	}

	private void applySettings() {
		JadxSettings settings = getSettings();
		codeScrollPane.setLineNumbersEnabled(settings.getLineNumbersMode() != LineNumbersMode.DISABLE);
		codeScrollPane.getGutter().setLineNumberFont(settings.getFont());
		scriptArea.loadSettings();
	}

	@Override
	public AbstractCodeArea getCodeArea() {
		return scriptArea;
	}

	@Override
	public void loadSettings() {
		applySettings();
		updateUI();
	}

	public void dispose() {
		scriptArea.dispose();
	}
}