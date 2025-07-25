/*
 * MCreator (https://mcreator.net/)
 * Copyright (C) 2020 Pylo and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package net.mcreator.ui.views.editor.image;

import net.mcreator.io.FileIO;
import net.mcreator.io.tree.FileNode;
import net.mcreator.io.zip.ZipIO;
import net.mcreator.minecraft.RegistryNameFixer;
import net.mcreator.ui.MCreator;
import net.mcreator.ui.MCreatorTabs;
import net.mcreator.ui.component.util.PanelUtils;
import net.mcreator.ui.component.zoompane.JZoomPane;
import net.mcreator.ui.dialogs.MCreatorDialog;
import net.mcreator.ui.dialogs.imageeditor.FromTemplateDialog;
import net.mcreator.ui.init.L10N;
import net.mcreator.ui.laf.themes.Theme;
import net.mcreator.ui.validation.component.VTextField;
import net.mcreator.ui.validation.validators.RegistryNameValidator;
import net.mcreator.ui.views.ViewBase;
import net.mcreator.ui.views.editor.image.canvas.Canvas;
import net.mcreator.ui.views.editor.image.canvas.CanvasRenderer;
import net.mcreator.ui.views.editor.image.canvas.SelectedBorder;
import net.mcreator.ui.views.editor.image.clipboard.ClipboardManager;
import net.mcreator.ui.views.editor.image.color.PalettePanel;
import net.mcreator.ui.views.editor.image.layer.Layer;
import net.mcreator.ui.views.editor.image.layer.LayerPanel;
import net.mcreator.ui.views.editor.image.metadata.MetadataManager;
import net.mcreator.ui.views.editor.image.metadata.MetadataOutdatedException;
import net.mcreator.ui.views.editor.image.tool.ToolPanel;
import net.mcreator.ui.views.editor.image.versioning.VersionManager;
import net.mcreator.ui.workspace.resources.TextureType;
import net.mcreator.util.FilenameUtilsPatched;
import net.mcreator.util.image.ImageUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ImageMakerView extends ViewBase implements MouseListener, MouseMotionListener {
	private static final Logger LOG = LogManager.getLogger("Image Maker View");
	private static final int FPS = 4;

	public static final ExecutorService toolExecutor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable);
		thread.setName("ImageMakerToolExecutor");
		thread.setUncaughtExceptionHandler((t, e) -> LOG.error(e));
		return thread;
	});

	private final JZoomPane zoomPane;
	private final CanvasRenderer canvasRenderer;
	private final JSplitPane leftSplitPane;
	private final JSplitPane rightSplitPane;
	private final JSplitPane paletteLayerSplitPane;
	private final ToolPanel toolPanel;
	private final PalettePanel palettePanel;
	private final LayerPanel layerPanel;
	private final VersionManager versionManager;
	private final ClipboardManager clipboardManager;
	private final JLabel imageInfo = new JLabel("");

	public final JButton save;

	private String name = L10N.t("tab.image_maker");
	private MCreatorTabs.Tab tab;
	private File image;
	private Canvas canvas;
	private Cursor currentCursor = null;
	private boolean active;
	private boolean canEdit = true;

	public ImageMakerView(MCreator f) {
		super(f);

		versionManager = new VersionManager(this);
		clipboardManager = new ClipboardManager(this);

		JPanel controls = new JPanel(new BorderLayout());
		controls.setBorder(new EmptyBorder(2, 3, 2, 3));
		JPanel leftControls = new JPanel(new FlowLayout(FlowLayout.LEFT));
		JPanel rightControls = new JPanel(new FlowLayout(FlowLayout.RIGHT));

		save = L10N.button("dialog.image_maker.save");
		save.setMargin(new Insets(1, 40, 1, 40));
		save.setBackground(Theme.current().getInterfaceAccentColor());
		save.setForeground(Theme.current().getSecondAltBackgroundColor());
		save.setFocusPainted(false);

		imageInfo.setForeground((Theme.current().getAltForegroundColor()).darker());
		imageInfo.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));

		JButton saveNew = L10N.button("dialog.image_maker.save_as_new");
		saveNew.setMargin(new Insets(1, 40, 1, 40));
		saveNew.setBackground(Theme.current().getAltBackgroundColor());
		saveNew.setForeground(Theme.current().getForegroundColor());
		saveNew.setFocusPainted(false);

		JButton template = L10N.button("dialog.image_maker.generate_from_template");
		template.setMargin(new Insets(1, 40, 1, 40));
		template.setBackground(Theme.current().getAltBackgroundColor());
		template.setForeground(Theme.current().getForegroundColor());
		template.setFocusPainted(false);

		save.addActionListener(event -> save());
		saveNew.addActionListener(event -> saveAs());
		template.addActionListener(event -> {
			FromTemplateDialog fromTemplateDialog = new FromTemplateDialog(f, canvas, versionManager);
			fromTemplateDialog.setVisible(true);
		});

		leftSplitPane = new JSplitPane();
		rightSplitPane = new JSplitPane() {
			@Override protected void paintComponent(Graphics g) {
				Graphics2D g2d = (Graphics2D) g.create();
				g2d.setColor(Theme.current().getAltBackgroundColor());
				g2d.setComposite(AlphaComposite.SrcOver.derive(0.45f));
				g2d.fillRect(0, 0, getWidth(), getHeight());
				g2d.dispose();
				super.paintComponent(g);
			}
		};
		paletteLayerSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);

		canvasRenderer = new CanvasRenderer(this);
		zoomPane = new JZoomPane(canvasRenderer);
		toolPanel = new ToolPanel(f, canvas, zoomPane, canvasRenderer, versionManager);

		palettePanel = new PalettePanel(f, toolPanel);
		layerPanel = new LayerPanel(f, toolPanel, versionManager);

		toolPanel.setLayerPanel(layerPanel);
		versionManager.setLayerPanel(layerPanel);

		leftSplitPane.setOpaque(false);
		rightSplitPane.setOpaque(false);
		paletteLayerSplitPane.setOpaque(false);

		leftSplitPane.setLeftComponent(toolPanel);
		leftSplitPane.setRightComponent(rightSplitPane);
		leftSplitPane.setOneTouchExpandable(true);

		rightSplitPane.setLeftComponent(PanelUtils.northAndCenterElement(imageInfo, zoomPane));
		rightSplitPane.setRightComponent(paletteLayerSplitPane);
		rightSplitPane.setResizeWeight(1);
		rightSplitPane.setOneTouchExpandable(true);

		paletteLayerSplitPane.setTopComponent(palettePanel);
		paletteLayerSplitPane.setBottomComponent(layerPanel);
		paletteLayerSplitPane.setOneTouchExpandable(true);

		leftControls.add(template);

		if (TextureType.getSupportedTypes(mcreator.getWorkspace(), false).length > 0)
			rightControls.add(saveNew);

		rightControls.add(save);

		controls.add(leftControls, BorderLayout.WEST);
		controls.add(rightControls, BorderLayout.EAST);

		controls.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.current().getSecondAltBackgroundColor()));

		add(controls, BorderLayout.NORTH);
		add(leftSplitPane, BorderLayout.CENTER);

		Thread animator = new Thread(() -> {
			active = true;
			while (active) {
				if (canvas != null && canvas.getSelection() != null
						&& canvas.getSelection().getEditing() != SelectedBorder.NONE) {
					canvasRenderer.addPhaseToOutline((float) Math.PI / FPS / 2);
					repaint();
				}

				try {
					Thread.sleep(1000 / FPS);
				} catch (InterruptedException e) {
					LOG.error(e.getMessage(), e);
				}
			}
		}, "ImageMakerAnimationRenderer");

		animator.start();
	}

	public void openInEditMode(File image) {
		try {
			this.image = image;
			try {
				// references will be updated
				canvas = MetadataManager.loadCanvasForFile(mcreator.getWorkspace(), image, this);
			} catch (NullPointerException e) {
				createCanvasFromBufferedImage(ImageIO.read(image));
			} catch (MetadataOutdatedException e) {
				Canvas old = e.getCanvas();
				if (old != null) {
					String[] options = { L10N.t("dialog.image_maker.load_new_version"),
							L10N.t("dialog.image_maker.load_old_version") };
					int option = JOptionPane.showOptionDialog(mcreator, L10N.t("dialog.image_maker.metadata_outdated"),
							L10N.t("dialog.image_maker.metadata_outdated.title"), JOptionPane.YES_NO_OPTION,
							JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
					if (option == 1) {
						// references were already updated during deserialization
						// in MetadataManager before MetadataOutdatedException was thrown
						canvas = old;
					} else {
						createCanvasFromBufferedImage(ImageIO.read(image));
					}
				} else {
					createCanvasFromBufferedImage(ImageIO.read(image));
				}
			}
			name = image.getName();
			toolPanel.initTools();
			updateInfoBar(0, 0);
		} catch (IOException e) {
			LOG.warn("Failed to open image", e);
		}
	}

	private void createCanvasFromBufferedImage(BufferedImage bufferedImage) throws IOException {
		Layer layer = Layer.toLayer(bufferedImage, image.getName());
		canvas = new Canvas(this, layer.getWidth(), layer.getHeight());
		canvas.add(layer);
	}

	public void openInReadOnlyMode(FileNode<?> image) {
		try {
			String[] path = image.splitPath();
			canEdit = false;

			createCanvasFromBufferedImage(
					Objects.requireNonNull(ZipIO.readFileInZip(new File(path[0]), path[1], (file, entry) -> {
						try {
							return ImageIO.read(file.getInputStream(entry));
						} catch (IOException e) {
							LOG.error(e.getMessage(), e);
							return null;
						}
					}), "Could not read source image asset!"));
			name = FilenameUtilsPatched.getName(path[1]);
			toolPanel.initTools();
			updateInfoBar(0, 0);
		} catch (IOException e) {
			LOG.warn("Failed to open image", e);
		}
	}

	public void setSaveLocation(File location) {
		this.image = location;
		name = image.getName();
		refreshTab();
	}

	public void save() {
		try {
			if (image != null) {
				ImageIO.write(canvasRenderer.render(), FilenameUtilsPatched.getExtension(image.toString()), image);
				MetadataManager.saveCanvas(mcreator.getWorkspace(), image, canvas);

				this.name = image.getName();

				//reload image in java cache
				new ImageIcon(image.getAbsolutePath()).getImage().flush();
				mcreator.reloadWorkspaceTabContents();

				refreshTab();
			} else {
				saveAs();
			}
		} catch (IOException e) {
			LOG.error(e.getMessage(), e);
		}
	}

	public void saveAs() {
		if (!canEdit) {
			int option = JOptionPane.showConfirmDialog(mcreator, L10N.t("dialog.image_maker.save.view_only"),
					L10N.t("common.confirmation"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.INFORMATION_MESSAGE);
			if (option != JOptionPane.OK_OPTION)
				return;
		}

		Image image = canvasRenderer.render();

		JComboBox<TextureType> types = new JComboBox<>(TextureType.getSupportedTypes(mcreator.getWorkspace(), false));
		VTextField name = new VTextField(20);
		name.setValidator(new RegistryNameValidator(name, L10N.t("dialog.image_maker.texture_name")));
		name.enableRealtimeValidation();

		MCreatorDialog typeDialog = new MCreatorDialog(mcreator, L10N.t("dialog.image_maker.texture_type.title"), true);

		JPanel panel = new JPanel(new GridLayout(2, 2, 5, 5));

		panel.add(L10N.label("dialog.image_maker.enter_name"));
		panel.add(name);
		panel.add(L10N.label("dialog.image_maker.texture_type.message"));
		panel.add(types);

		JButton ok = L10N.button("dialog.image_maker.save");
		ok.addActionListener(e -> {
			typeDialog.dispose();
			TextureType textureType = (TextureType) types.getSelectedItem();

			if (name.getText() != null && !name.getText().isEmpty() && textureType != null) {
				File exportFile = mcreator.getFolderManager()
						.getTextureFile(RegistryNameFixer.fix(name.getText()), textureType);

				if (exportFile.isFile()) {
					JOptionPane.showMessageDialog(mcreator, L10N.t("dialog.image_maker.texture_type_name_exists"),
							L10N.t("dialog.image_maker.resource_error"), JOptionPane.ERROR_MESSAGE);
				} else {
					FileIO.writeImageToPNGFile(ImageUtils.toBufferedImage(image), exportFile);
					MetadataManager.saveCanvas(mcreator.getWorkspace(), exportFile, canvas);

					// load image in java cache
					new ImageIcon(exportFile.getAbsolutePath()).getImage().flush();

					this.image = exportFile;
					this.name = this.image.getName();
					refreshTab();
				}
			}
		});
		JButton cancel = L10N.button("common.cancel");
		cancel.addActionListener(e -> typeDialog.dispose());

		JPanel buttonsPanel = new JPanel();
		buttonsPanel.add(ok);
		buttonsPanel.add(cancel);
		buttonsPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

		typeDialog.add(PanelUtils.centerAndSouthElement(PanelUtils.totalCenterInPanel(panel),
				PanelUtils.totalCenterInPanel(buttonsPanel)));

		typeDialog.getRootPane().setDefaultButton(ok);

		typeDialog.setSize(550, 150);
		typeDialog.setLocationRelativeTo(null);
		typeDialog.setVisible(true);
	}

	public void newImage(Layer layer) {
		canvas = new Canvas(this, layer.getWidth(), layer.getHeight());
		canvas.add(layer);
		this.name = L10N.t("tab.image_maker");
		toolPanel.initTools();
		updateInfoBar(0, 0);
	}

	@Override public ViewBase showView() {
		if (image != null)
			this.tab = new MCreatorTabs.Tab(this, image);
		else
			this.tab = new MCreatorTabs.Tab(this);

		tab.setTabClosedListener(tab -> {
			this.active = false;
			palettePanel.storePalette();
		});
		tab.setTabHiddenListener(tab -> palettePanel.storePalette());
		tab.setTabShownListener(tab -> palettePanel.reloadPalette());

		MCreatorTabs.Tab existing = mcreator.getTabs().showTabOrGetExisting(this.tab);
		if (existing == null) {
			mcreator.getTabs().addTab(this.tab);
			leftSplitPane.setDividerLocation(0.16);
			rightSplitPane.setDividerLocation(0.79);
			paletteLayerSplitPane.setDividerLocation(0.5);
			zoomPane.getZoomport().fitZoom();
			refreshTab();
			return this;
		}
		refreshTab();
		return (ViewBase) existing.getContent();
	}

	@Override public String getViewName() {
		return name;
	}

	@Override public ImageIcon getViewIcon() {
		if (canvasRenderer != null)
			return ImageUtils.fit(canvasRenderer.render(), 24);
		return null;
	}

	public void refreshTab() {
		if (tab != null) {
			tab.setIcon(getViewIcon());
			tab.setText(this.name);
			tab.updateSize();
		}
	}

	@Override public void mouseClicked(MouseEvent e) {
		toolExecutor.execute(() -> toolPanel.getCurrentTool().mouseClicked(e));
	}

	@Override public void mousePressed(MouseEvent e) {
		setEditorCursor(toolPanel.getCurrentTool().getUsingCursor());
		toolExecutor.execute(() -> toolPanel.getCurrentTool().mousePressed(e));
	}

	@Override public void mouseReleased(MouseEvent e) {
		setEditorCursor(toolPanel.getCurrentTool().getCursor());
		toolExecutor.execute(() -> toolPanel.getCurrentTool().mouseReleased(e));
	}

	@Override public void mouseEntered(MouseEvent e) {
		toolExecutor.execute(() -> toolPanel.getCurrentTool().mouseEntered(e));
	}

	@Override public void mouseExited(MouseEvent e) {
		toolExecutor.execute(() -> toolPanel.getCurrentTool().mouseExited(e));
	}

	@Override public void mouseDragged(MouseEvent e) {
		setEditorCursor(toolPanel.getCurrentTool().getUsingCursor());
		toolExecutor.execute(() -> toolPanel.getCurrentTool().mouseDragged(e));
		updateInfoBar(e.getX(), e.getY());
	}

	@Override public void mouseMoved(MouseEvent e) {
		if (toolPanel.getCurrentTool().getHoverCursor() != null) {
			setEditorCursor(toolPanel.getCurrentTool().getHoverCursor());
		}
		toolExecutor.execute(() -> toolPanel.getCurrentTool().mouseMoved(e));
		updateInfoBar(e.getX(), e.getY());
	}

	public void setEditorCursor(Cursor cursor) {
		if (currentCursor == cursor)
			return;
		currentCursor = cursor;

		SwingUtilities.invokeLater(() -> {
			zoomPane.getZoomport().setCursor(cursor);
			canvasRenderer.setCursor(cursor);
		});
	}

	private void updateInfoBar(int x, int y) {
		String title;
		if (image != null)
			title = L10N.t("dialog.image_maker.info_bar.file", image.getName());
		else if (!canEdit)
			title = L10N.t("dialog.image_maker.info_bar.source_image", name);
		else
			title = L10N.t("dialog.image_maker.info_bar.new_image");

		imageInfo.setText(L10N.t("dialog.image_maker.info_bar", title, canvas.getWidth(), canvas.getHeight(), x, y));
	}

	public CanvasRenderer getCanvasRenderer() {
		return canvasRenderer;
	}

	public VersionManager getVersionManager() {
		return versionManager;
	}

	public ClipboardManager getClipboardManager() {
		return clipboardManager;
	}

	public ToolPanel getToolPanel() {
		return toolPanel;
	}

	public Canvas getCanvas() {
		return canvas;
	}

	public LayerPanel getLayerPanel() {
		return layerPanel;
	}

	public File getImageFile() {
		return image;
	}

	public static boolean isFileSupported(String fileName) {
		return Arrays.asList("bmp", "gif", "jpeg", "jpg", "png", "tiff", "tif", "wbmp")
				.contains(FilenameUtilsPatched.getExtension(fileName));
	}

}
