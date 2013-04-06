package jsettlers.input;

import java.util.LinkedList;
import java.util.List;

import jsettlers.common.CommonConstants;
import jsettlers.common.buildings.EBuildingType;
import jsettlers.common.buildings.IBuilding;
import jsettlers.common.map.shapes.MapCircle;
import jsettlers.common.map.shapes.MapShapeFilter;
import jsettlers.common.material.EPriority;
import jsettlers.common.movable.EMovableType;
import jsettlers.common.movable.IMovable;
import jsettlers.common.position.ILocatable;
import jsettlers.common.position.ShortPoint2D;
import jsettlers.common.selectable.ESelectionType;
import jsettlers.common.selectable.ISelectable;
import jsettlers.graphics.action.Action;
import jsettlers.graphics.action.BuildAction;
import jsettlers.graphics.action.ConvertAction;
import jsettlers.graphics.action.EActionType;
import jsettlers.graphics.action.PointAction;
import jsettlers.graphics.action.ScreenChangeAction;
import jsettlers.graphics.action.SelectAreaAction;
import jsettlers.graphics.action.SetBuildingPriorityAction;
import jsettlers.graphics.action.SetMaterialDistributionSettingsAction;
import jsettlers.graphics.map.IMapInterfaceListener;
import jsettlers.graphics.map.MapInterfaceConnector;
import jsettlers.input.task.ConvertGuiTask;
import jsettlers.input.task.DestroyBuildingGuiTask;
import jsettlers.input.task.EGuiAction;
import jsettlers.input.task.GeneralGuiTask;
import jsettlers.input.task.MovableGuiTask;
import jsettlers.input.task.MoveToGuiTask;
import jsettlers.input.task.SetBuildingPriorityGuiTask;
import jsettlers.input.task.SetMaterialDistributionSettingsGuiTask;
import jsettlers.input.task.SimpleGuiTask;
import jsettlers.input.task.WorkAreaGuiTask;
import jsettlers.logic.algorithms.construction.ConstructionMarksThread;
import jsettlers.logic.buildings.Building;
import jsettlers.logic.newmovable.interfaces.IDebugable;
import jsettlers.logic.newmovable.interfaces.IIDable;
import network.NetworkManager;
import synchronic.timer.NetworkTimer;

/**
 * class to handle the events provided by the user through jsettlers.graphics
 * 
 * @author Andreas Eberle
 */
public class GuiInterface implements IMapInterfaceListener, ITaskExecutorGuiInterface {

	private final MapInterfaceConnector connector;

	private final ConstructionMarksThread constructionMarksCalculator;
	private final NetworkManager manager;
	private final IGuiInputGrid grid;
	private final byte player;
	/**
	 * The current active action that waits for the user to select a point.
	 */
	private Action activeAction = null;
	private EBuildingType previewBuilding;
	private SelectionSet currentSelection = new SelectionSet();

	public GuiInterface(MapInterfaceConnector connector, NetworkManager manager, IGuiInputGrid grid, byte player) {
		this.connector = connector;
		this.manager = manager;
		this.grid = grid;
		this.player = player;
		this.constructionMarksCalculator = new ConstructionMarksThread(grid.getConstructionMarksGrid(), player);
		connector.addListener(this);
	}

	@Override
	public void action(Action action) {
		if (action.getActionType() != EActionType.SCREEN_CHANGE) {
			System.out.println("action(Action): " + action.getActionType());
		}

		switch (action.getActionType()) {
		case BUILD:
			this.setSelection(new SelectionSet());
			EBuildingType buildingType = ((BuildAction) action).getBuilding();
			System.out.println("build: " + buildingType);
			this.previewBuilding = buildingType; // FIXME implement a way to give graphics grid the preview building
			connector.setPreviewBuildingType(buildingType);
			constructionMarksCalculator.setBuildingType(buildingType);
			setActiveAction(action);
			break;

		case DEBUG_ACTION:
			for (ISelectable curr : currentSelection) {
				if (curr instanceof IDebugable) {
					((IDebugable) curr).debug();
				}
			}
			break;

		case SPEED_TOGGLE_PAUSE:
			NetworkTimer.get().invertPausing();
			break;

		case SPEED_SLOW:
			if (!manager.isMultiplayer()) {
				NetworkTimer.setGameSpeed(0.5f);
			}
			break;
		case SPEED_FAST:
			if (!manager.isMultiplayer()) {
				NetworkTimer.setGameSpeed(2.0f);
			}
			break;
		case SPEED_FASTER:
			if (!manager.isMultiplayer()) {
				NetworkTimer.multiplyGameSpeed(1.2f);
			}
			break;
		case SPEED_SLOWER:
			if (!manager.isMultiplayer()) {
				NetworkTimer.multiplyGameSpeed(1 / 1.2f);
			}
			break;
		case SPEED_NORMAL:
			if (!manager.isMultiplayer()) {
				NetworkTimer.setGameSpeed(1.0f);
			}
			break;

		case FAST_FORWARD:
			if (!manager.isMultiplayer()) {
				NetworkTimer.get().fastForward();
			}
			break;

		case SELECT_POINT:
			handleSelectPointAction((PointAction) action);
			break;

		case SELECT_AREA:
			selectArea((SelectAreaAction) action);
			break;

		case MOVE_TO:
			if (previewBuilding != null) { // cancel building creation
				cancelBuildingCreation();
				setActiveAction(null);
			} else {
				PointAction moveToAction = (PointAction) action;

				if (currentSelection.getSelectionType() == ESelectionType.BUILDING && currentSelection.getSize() == 1) {
					setBuildingWorkArea(moveToAction.getPosition());

				} else {
					moveTo(moveToAction.getPosition());
				}
			}
			break;

		case SET_WORK_AREA:
			if (currentSelection.getSize() > 0) {
				setBuildingWorkArea(((PointAction) action).getPosition());
			}
			break;

		case DESTROY:
			destroySelected();
			break;

		case STOP_WORKING:
			stopOrStartWorkingAction(true);
			break;
		case START_WORKING:
			stopOrStartWorkingAction(false);
			break;

		case SHOW_SELECTION:
			showSelection();
			break;

		case SCREEN_CHANGE:
			constructionMarksCalculator.setScreen(((ScreenChangeAction) action).getScreenArea());
			break;

		case TOGGLE_DEBUG:
			grid.resetDebugColors();
			break;

		case TOGGLE_FOG_OF_WAR:
			grid.toggleFogOfWar();
			break;

		case SAVE:
			manager.scheduleTask(new SimpleGuiTask(EGuiAction.QUICK_SAVE));
			break;

		case CONVERT:
			sendConvertAction((ConvertAction) action);
			break;

		case SET_BUILDING_PRIORITY:
			setBuildingPriority(((SetBuildingPriorityAction) action).getNewPriority());
			break;

		case SET_MATERIAL_DISTRIBUTION_SETTINGS: {
			SetMaterialDistributionSettingsAction task = (SetMaterialDistributionSettingsAction) action;
			manager.scheduleTask(new SetMaterialDistributionSettingsGuiTask(task.getManagerPosition(), task.getMaterialType(), task
					.getProbabilities()));
		}
			break;

		default:
			System.err.println("GuiInterface.action() called, but event can't be handled... (" + action.getActionType() + ")");
		}
	}

	private void setBuildingWorkArea(ShortPoint2D position) {
		ISelectable selected = currentSelection.iterator().next();
		if (selected instanceof Building) {
			scheduleTask(new WorkAreaGuiTask(EGuiAction.SET_WORK_AREA, position, ((Building) selected).getPos()));
		}
	}

	private void sendConvertAction(ConvertAction action) {
		List<ISelectable> convertables = new LinkedList<ISelectable>();
		switch (action.getTargetType()) {
		case BEARER:
			for (ISelectable curr : currentSelection) {
				if (curr instanceof IMovable) {
					EMovableType currType = ((IMovable) curr).getMovableType();
					if (currType == EMovableType.THIEF || currType == EMovableType.PIONEER || currType == EMovableType.GEOLOGIST) {
						convertables.add(curr);
						if (convertables.size() >= action.getAmount()) {
							break;
						}
					}
				}
			}
			break;
		case PIONEER:
		case GEOLOGIST:
		case THIEF:
			for (ISelectable curr : currentSelection) {
				if (curr instanceof IMovable) {
					EMovableType currType = ((IMovable) curr).getMovableType();
					if (currType == EMovableType.BEARER) {
						convertables.add(curr);
						if (convertables.size() >= action.getAmount()) {
							break;
						}
					}
				}
			}
			break;
		default:
			System.err.println("WARNING: can't handle convert to this movable type: " + action.getTargetType());
			return;
		}

		if (convertables.size() > 0) {
			manager.scheduleTask(new ConvertGuiTask(getIDsOfIterable(convertables), action.getTargetType()));
		}
	}

	private void cancelBuildingCreation() {
		previewBuilding = null;
		constructionMarksCalculator.setBuildingType(null);
		connector.setPreviewBuildingType(null);
	}

	private void destroySelected() {
		if (currentSelection == null || currentSelection.getSize() == 0) {
			return;
		} else if (currentSelection.getSize() == 1 && currentSelection.iterator().next() instanceof Building) {
			manager.scheduleTask(new DestroyBuildingGuiTask(((Building) currentSelection.iterator().next()).getPos()));
		} else {
			manager.scheduleTask(new MovableGuiTask(EGuiAction.DESTROY_MOVABLES, getIDsOfSelected()));
		}
		setSelection(new SelectionSet());
	}

	private void setBuildingPriority(EPriority newPriority) {
		if (currentSelection != null && currentSelection.getSize() == 1 && currentSelection.iterator().next() instanceof Building) {
			manager.scheduleTask(new SetBuildingPriorityGuiTask(((Building) currentSelection.iterator().next()).getPos(), newPriority));
		}
	}

	private void showSelection() {
		int x = 0;
		int y = 0;
		int count = 0;
		for (ISelectable member : currentSelection) {
			if (member instanceof ILocatable) {
				x += ((ILocatable) member).getPos().x;
				y += ((ILocatable) member).getPos().y;
				count++;
			}
		}
		System.out.println("locatable: " + count);
		if (count > 0) {
			ShortPoint2D point = new ShortPoint2D(x / count, y / count);
			connector.scrollTo(point, false);
		}
	}

	/**
	 * @param stop
	 *            if true the members of currentSelection will stop working<br>
	 *            if false, they will start working
	 */
	private void stopOrStartWorkingAction(boolean stop) {
		manager.scheduleTask(new MovableGuiTask(stop ? EGuiAction.STOP_WORKING : EGuiAction.START_WORKING, getIDsOfSelected()));
	}

	private void moveTo(ShortPoint2D pos) {
		List<Integer> selectedIds = getIDsOfSelected();
		scheduleTask(new MoveToGuiTask(pos, selectedIds));
	}

	private final List<Integer> getIDsOfSelected() {
		return getIDsOfIterable(currentSelection);
	}

	private final static List<Integer> getIDsOfIterable(Iterable<? extends ISelectable> iterable) {
		List<Integer> selectedIds = new LinkedList<Integer>();

		for (ISelectable curr : iterable) {
			if (curr instanceof IIDable) {
				selectedIds.add(((IIDable) curr).getID());
			}
		}
		return selectedIds;
	}

	private void setActiveAction(Action action) {
		if (this.activeAction != null) {
			// TODO: if it was a build action, remove build images
			this.activeAction.setActive(false);
		}
		this.activeAction = action;
		if (action != null) {
			action.setActive(true);
		}
	}

	private void selectArea(SelectAreaAction action) {
		SelectionSet selectionSet = new SelectionSet();

		for (ShortPoint2D curr : new MapShapeFilter(action.getArea(), grid.getWidth(), grid.getHeight())) {
			IGuiMovable movable = grid.getMovable(curr.x, curr.y);
			if (movable != null && (CommonConstants.ENABLE_ALL_PLAYER_SELECTION || movable.getPlayerId() == player)) {
				selectionSet.add(movable);
			}
			IBuilding building = grid.getBuildingAt(curr.x, curr.y);
			if (building != null && (CommonConstants.ENABLE_ALL_PLAYER_SELECTION || building.getPlayerId() == player)) {
				selectionSet.add(building);
			}
		}

		setSelection(selectionSet);
	}

	private void handleSelectPointAction(PointAction action) {
		ShortPoint2D pos = action.getPosition();

		// only for debugging
		grid.postionClicked(pos.x, pos.y);

		// check what's to do
		if (activeAction == null) {
			select(pos);
		} else {
			switch (activeAction.getActionType()) {
			case BUILD:
				EBuildingType type = previewBuilding;
				ShortPoint2D pos2 = grid.getConstructablePosition(pos, type, InputSettings.USE_NEIGHBOR_POSITIONS_FOR_CONSTRUCTION);
				if (pos2 != null) {
					cancelBuildingCreation();
					scheduleTask(new GeneralGuiTask(EGuiAction.BUILD, pos2, type));
					break;
				} else {
					return; // prevent resetting the current action
				}
			default:
				break;
			}

			setActiveAction(null);
		}
	}

	private void scheduleTask(SimpleGuiTask guiTask) {
		manager.scheduleTask(guiTask);
	}

	private void select(ShortPoint2D pos) {
		if (grid.isInBounds(pos)) {
			short x = pos.x;
			short y = pos.y;

			IGuiMovable m1 = grid.getMovable(x, y);
			IGuiMovable m3 = grid.getMovable((short) (x + 1), (short) (y + 1));
			IGuiMovable m2 = grid.getMovable((x), (short) (y + 1));
			IGuiMovable m4 = grid.getMovable((short) (x + 1), (short) (y + 2));

			if (m1 != null) {
				setSelection(new SelectionSet(m1));
				System.out.println("found movable at selection pos: " + pos);
			} else if (m2 != null) {
				setSelection(new SelectionSet(m2));
			} else if (m3 != null) {
				setSelection(new SelectionSet(m3));
			} else if (m4 != null) {
				setSelection(new SelectionSet(m4));

			} else {
				// search buildings
				IBuilding building = getBuildingAround(pos);
				if (building != null) {
					setSelection(new SelectionSet(building));
				} else {
					setSelection(new SelectionSet());
				}
			}
		}
	}

	private IBuilding getBuildingAround(ShortPoint2D pos) {
		for (ShortPoint2D curr : new MapCircle(pos.x, pos.y, 5)) {
			if (grid.isInBounds(curr)) {
				IBuilding building = grid.getBuildingAt(curr.x, curr.y);
				if (building != null) {
					return building;
				}
			}
		}
		return null;
	}

	/**
	 * Sets the selection.
	 * 
	 * @param selection
	 *            The selected items. Not null!
	 */
	private void setSelection(SelectionSet selection) {
		currentSelection.clear();

		selection.setSelected(true);
		this.connector.setSelection(selection);
		this.currentSelection = selection;
	}

	@Override
	public void refreshSelection() {
		connector.setSelection(null);
		connector.setSelection(currentSelection);
	}

	/**
	 * Shuts down used threads.
	 */
	public void stop() {
		constructionMarksCalculator.cancel();
		connector.removeListener(this);
	}

}
