package com.marginallyclever.loaders;

import java.awt.GridLayout;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import javax.swing.JCheckBox;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.kabeja.dxf.Bounds;
import org.kabeja.dxf.DXFConstants;
import org.kabeja.dxf.DXFDocument;
import org.kabeja.dxf.DXFEntity;
import org.kabeja.dxf.DXFLWPolyline;
import org.kabeja.dxf.DXFLayer;
import org.kabeja.dxf.DXFLine;
import org.kabeja.dxf.DXFPolyline;
import org.kabeja.dxf.DXFSpline;
import org.kabeja.dxf.DXFVertex;
import org.kabeja.dxf.helpers.DXFSplineConverter;
import org.kabeja.dxf.helpers.Point;
import org.kabeja.parser.DXFParser;
import org.kabeja.parser.ParseException;
import org.kabeja.parser.Parser;
import org.kabeja.parser.ParserBuilder;

import com.marginallyclever.basictypes.ImageManipulator;
import com.marginallyclever.makelangelo.Log;
import com.marginallyclever.makelangelo.Translator;
import com.marginallyclever.makelangeloRobot.MakelangeloRobot;

/**
 * Reads in DXF file, optimizes the pathing a bit, converts it to a temporary gcode file, then calls LoadGCode.
 * @author Dan Royer
 *
 */
public class LoadDXF2 extends ImageManipulator implements LoadFileType {
	boolean shouldScaleOnLoad=true;
	
	@Override
	public String getName() { return "DXF + sort"; }
	
	@Override
	public FileNameExtensionFilter getFileNameFilter() {
		return new FileNameExtensionFilter(Translator.get("FileTypeDXF2"), "dxf");
	}

	@Override
	public boolean canLoad(String filename) {
		String ext = filename.substring(filename.lastIndexOf('.'));
		return (ext.equalsIgnoreCase(".dxf"));
	}

	@Override
	public boolean load(InputStream in,MakelangeloRobot robot) {
		final JCheckBox checkScale = new JCheckBox(Translator.get("DXFScaleOnLoad"));

		JPanel panel = new JPanel(new GridLayout(0, 1));
		panel.add(checkScale);
		checkScale.setSelected(shouldScaleOnLoad);

		int result = JOptionPane.showConfirmDialog(null, panel, getName(), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (result == JOptionPane.OK_OPTION) {
			shouldScaleOnLoad = checkScale.isSelected();
			
			return loadNow(in,robot);
		}
		return false;
	}

	
	protected void sortGroupsByProximity(List<DXFGroup> groups) {
		Log.message("Sorting groups by proximity...");
	}

	/**
	 * Put every entity into a bucket.
	 * @param doc
	 * @param grid
	 * @param groups
	 */
	@SuppressWarnings("unchecked")
	protected void sortEntitiesIntoBucketsAndGroups(DXFDocument doc,DXFBucketGrid grid,List<DXFGroup> groups) {
		Log.message("Sorting into buckets...");
		
		Iterator<DXFLayer> layerIter = (Iterator<DXFLayer>)doc.getDXFLayerIterator();
		while (layerIter.hasNext()) {
			DXFLayer layer = (DXFLayer) layerIter.next();

			Iterator<String> entityTypeIter = (Iterator<String>) layer.getDXFEntityTypeIterator();
			while (entityTypeIter.hasNext()) {
				String entityType = (String) entityTypeIter.next();
				List<DXFEntity> entityList = (List<DXFEntity>)layer.getDXFEntities(entityType);
				Iterator<DXFEntity> iter = entityList.iterator();
				while(iter.hasNext()) {
					DXFEntity e = iter.next();
					DXFBucketEntity be = new DXFBucketEntity(e);
					
					if (e.getType().equals(DXFConstants.ENTITY_TYPE_LINE)) {
						DXFLine line = (DXFLine)e;
						grid.addEntity(be, line.getStartPoint());
						grid.addEntity(be, line.getEndPoint());
						continue;
					}
					if(e.getType().equals(DXFConstants.ENTITY_TYPE_SPLINE)) {
						e = DXFSplineConverter.toDXFPolyline((DXFSpline)e);
						// fall through to the next case, polyline.
					}
					if(e.getType().equals(DXFConstants.ENTITY_TYPE_POLYLINE)) {
						DXFPolyline polyLine = (DXFPolyline)e;
						
						if(!polyLine.isClosed()) {
							grid.addEntity(be, polyLine.getVertex(0).getPoint());
							grid.addEntity(be, polyLine.getVertex(polyLine.getVertexCount()-1).getPoint());
						} else {
							grid.addEntity(be, polyLine.getVertex(0).getPoint());
							grid.addEntity(be, polyLine.getVertex(0).getPoint());
						}
						continue;
					}
					if(e.getType().equals(DXFConstants.ENTITY_TYPE_LWPOLYLINE)) {
						DXFLWPolyline polyLine = (DXFLWPolyline)e;
						if(!polyLine.isClosed()) {
							grid.addEntity(be, polyLine.getVertex(0).getPoint());
							grid.addEntity(be, polyLine.getVertex(polyLine.getVertexCount()-1).getPoint());
						} else {
							grid.addEntity(be, polyLine.getVertex(0).getPoint());
							grid.addEntity(be, polyLine.getVertex(0).getPoint());
						}
						continue;
					}
					// I don't know this entity type.
					Log.error("Unknown DXF type "+e.getType());
				}
			}
		}
		
		grid.countEntitiesInBuckets();
		
		// Use the buckets to narrow the search field and find neighboring entities
		grid.sortEntitiesIntoContinguousGroups(groups);
	}
	
	
	/**
	 * 
	 * @param in
	 * @param robot
	 * @return true if load is successful.
	 */
	@SuppressWarnings("unchecked")
	private boolean loadNow(InputStream in,MakelangeloRobot robot) {
		Log.message(Translator.get("FileTypeDXF2")+"...");
		String destinationFile = System.getProperty("user.dir") + "/temp.ngc";
		Log.message(Translator.get("Converting") + " " + destinationFile);

		// Read in the DXF file
		Parser parser = ParserBuilder.createDefaultParser();
		try {
			parser.parse(in, DXFParser.DEFAULT_ENCODING);
		} catch (ParseException e) {
			e.printStackTrace();
			return false;
		}		
		DXFDocument doc = parser.getDocument();

		// Sort the entities into the buckets.  Buckets are arranged in an XY grid.
		// All non-closed entities would appear in two buckets.  One Entity might be in the same bucket twice.
		Bounds bounds = doc.getBounds();
		Point topLeft = new Point();
		Point bottomRight = new Point();
		topLeft.setX(bounds.getMinimumX());
		topLeft.setY(bounds.getMinimumY());
		bottomRight.setX(bounds.getMaximumX());
		bottomRight.setY(bounds.getMaximumY());
		DXFBucketGrid grid = new DXFBucketGrid(15,15,topLeft,bottomRight);
		List<DXFGroup> groups = new LinkedList<DXFGroup>();
		sortEntitiesIntoBucketsAndGroups(doc,grid,groups);
		sortGroupsByProximity(groups);
		
		// convert each entity
		Bounds b = doc.getBounds();
		double imageCenterX = (b.getMaximumX() + b.getMinimumX()) / 2.0;
		double imageCenterY = (b.getMaximumY() + b.getMinimumY()) / 2.0;

		// find the scale to fit the image on the paper without
		// altering the aspect ratio
		double imageWidth  = (b.getMaximumX() - b.getMinimumX());
		double imageHeight = (b.getMaximumY() - b.getMinimumY());
		double paperHeight = robot.getSettings().getPaperHeight() * 10.0 * robot.getSettings().getPaperMargin();
		double paperWidth  = robot.getSettings().getPaperWidth () * 10.0 * robot.getSettings().getPaperMargin();

		double innerAspectRatio = imageWidth / imageHeight;
		double outerAspectRatio = paperWidth / paperHeight;
		double scale = 1;

		if(shouldScaleOnLoad) {
			scale = (innerAspectRatio >= outerAspectRatio) ?
					(paperWidth / imageWidth) :
					(paperHeight / imageHeight);
		}

		// count all entities in all layers
		Iterator<DXFLayer> layerIter = (Iterator<DXFLayer>) doc.getDXFLayerIterator();
		//int entityTotal = 0;
		while (layerIter.hasNext()) {
			DXFLayer layer = (DXFLayer) layerIter.next();
			Log.message("Found layer " + layer.getName());
			Iterator<String> entityIter = (Iterator<String>) layer.getDXFEntityTypeIterator();
			while (entityIter.hasNext()) {
				String entityType = (String) entityIter.next();
				List<DXFEntity> entityList = (List<DXFEntity>) layer.getDXFEntities(entityType);
				Log.message("Found " + entityList.size() + " of type " + entityType);
				//entityTotal += entityList.size();
			}
		}

		Log.message("Exporting...");
		try (FileOutputStream fileOutputStream = new FileOutputStream(destinationFile);
				Writer out = new OutputStreamWriter(fileOutputStream, StandardCharsets.UTF_8)) {

			machine = robot.getSettings();
			tool = machine.getCurrentTool();
			// gcode preamble
			out.write(machine.getGCodeConfig() + ";\n");
			out.write(machine.getGCodeBobbin() + ";\n");
			out.write(machine.getGCodeSetPositionAtHome()+";\n");
			
			setAbsoluteMode(out);
			tool.writeChangeTo(out);
			liftPen(out);
			previousX = machine.getHomeX();
			previousY = machine.getHomeY();
			
			double toolDiameterSquared = tool.getDiameter() * tool.getDiameter();
			
			// output all entities
			Iterator<DXFGroup> groupIter = groups.iterator();
			while(groupIter.hasNext()) {
				DXFGroup g = groupIter.next();
				Iterator<DXFBucketEntity> iter = g.entities.iterator();
				while(iter.hasNext()) {
					DXFEntity e = iter.next().entity;
					if (e.getType().equals(DXFConstants.ENTITY_TYPE_LINE)) {
						parseDXFLine(out,(DXFLine)e,scale,imageCenterX,imageCenterY,toolDiameterSquared);
					} else if (e.getType().equals(DXFConstants.ENTITY_TYPE_SPLINE)) {
						parseDXFPolyline(out,DXFSplineConverter.toDXFPolyline((DXFSpline)e),scale,imageCenterX,imageCenterY,toolDiameterSquared);
					} else if (e.getType().equals(DXFConstants.ENTITY_TYPE_POLYLINE)) {
						parseDXFPolyline(out,(DXFPolyline)e,scale,imageCenterX,imageCenterY,toolDiameterSquared);
					} else if (e.getType().equals(DXFConstants.ENTITY_TYPE_LWPOLYLINE)) {
						parseDXFLWPolyline(out,(DXFLWPolyline)e,scale,imageCenterX,imageCenterY,toolDiameterSquared);
					}
				}
			}
			
			// entities finished. Close up file.
			liftPen(out);
		    moveTo(out, (float)machine.getHomeX(), (float)machine.getHomeY(),true);
			
			out.flush();
			out.close();

			Log.message("Done!");
			LoadGCode loader = new LoadGCode();
			InputStream fileInputStream = new FileInputStream(destinationFile);
			loader.load(fileInputStream,robot);
		} catch (IOException e) {
			e.printStackTrace();
		}

		return true;
	}

	protected void parseDXFPolyline(Writer out,DXFPolyline entity,double scale,double imageCenterX,double imageCenterY,double toolDiameterSquared) throws IOException {
		if(entity.isClosed()) {
			// only one end to care about
			parseDXFPolylineForward(out,entity,scale,imageCenterX,imageCenterY,toolDiameterSquared);
		} else {
			// which end is closest to the previous (x,y)?
			int n = entity.getVertexCount()-1;
			double x = (entity.getVertex(0).getX() - imageCenterX) * scale;
			double y = (entity.getVertex(0).getY() - imageCenterY) * scale;
			double x2 = (entity.getVertex(n).getX() - imageCenterX) * scale;
			double y2 = (entity.getVertex(n).getY() - imageCenterY) * scale;

			// which end is closer to the previous (x,y) ?
			double dx = previousX - x;
			double dy = previousY - y;
			double dx2 = previousX - x2;
			double dy2 = previousY - y2;
			if ( dx * dx + dy * dy < dx2 * dx2 + dy2 * dy2 ) {
				// first point is closer
				parseDXFPolylineForward(out,entity,scale,imageCenterX,imageCenterY,toolDiameterSquared);
			} else {
				// last point is closer
				parseDXFPolylineBackward(out,entity,scale,imageCenterX,imageCenterY,toolDiameterSquared);
			}
		}
	}
	
	protected void parseDXFLWPolylineForward(Writer out,DXFLWPolyline entity,double scale,double imageCenterX,double imageCenterY,double toolDiameterSquared) throws IOException {
		boolean first = true;
		int count = entity.getVertexCount() + (entity.isClosed()?1:0);
		for (int j = 0; j < count; ++j) {
			DXFVertex v = entity.getVertex(j % entity.getVertexCount());
			double x = (v.getX() - imageCenterX) * scale;
			double y = (v.getY() - imageCenterY) * scale;
			parseCurvingLine(out,x,y,toolDiameterSquared,first,j<count-1);
			first = false;
		}
	}
	
	protected void parseDXFLWPolylineBackward(Writer out,DXFLWPolyline entity,double scale,double imageCenterX,double imageCenterY,double toolDiameterSquared) throws IOException {
		boolean first = true;
		int count = entity.getVertexCount() + (entity.isClosed()?1:0);
		for (int j = 0; j < count; ++j) {
			DXFVertex v = entity.getVertex((count+count-1-j) % entity.getVertexCount());
			double x = (v.getX() - imageCenterX) * scale;
			double y = (v.getY() - imageCenterY) * scale;
			parseCurvingLine(out,x,y,toolDiameterSquared,first,j<count-1);
			first = false;
		}
	}

	protected void parseDXFLWPolyline(Writer out,DXFLWPolyline entity,double scale,double imageCenterX,double imageCenterY,double toolDiameterSquared) throws IOException {
		if(entity.isClosed()) {
			// only one end to care about
			parseDXFLWPolylineForward(out,entity,scale,imageCenterX,imageCenterY,toolDiameterSquared);
		} else {
			// which end is closest to the previous (x,y)?
			int n = entity.getVertexCount()-1;
			double x = (entity.getVertex(0).getX() - imageCenterX) * scale;
			double y = (entity.getVertex(0).getY() - imageCenterY) * scale;
			double x2 = (entity.getVertex(n).getX() - imageCenterX) * scale;
			double y2 = (entity.getVertex(n).getY() - imageCenterY) * scale;

			// which end is closer to the previous (x,y) ?
			double dx = previousX - x;
			double dy = previousY - y;
			double dx2 = previousX - x2;
			double dy2 = previousY - y2;
			if ( dx * dx + dy * dy < dx2 * dx2 + dy2 * dy2 ) {
				// first point is closer
				parseDXFLWPolylineForward(out,entity,scale,imageCenterX,imageCenterY,toolDiameterSquared);
			} else {
				// last point is closer
				parseDXFLWPolylineBackward(out,entity,scale,imageCenterX,imageCenterY,toolDiameterSquared);
			}
		}
	}
	
	protected void parseDXFPolylineForward(Writer out,DXFPolyline entity,double scale,double imageCenterX,double imageCenterY,double toolDiameterSquared) throws IOException {
		boolean first = true;
		int count = entity.getVertexCount() + (entity.isClosed()?1:0);
		for (int j = 0; j < count; ++j) {
			DXFVertex v = entity.getVertex(j % entity.getVertexCount());
			double x = (v.getX() - imageCenterX) * scale;
			double y = (v.getY() - imageCenterY) * scale;
			parseCurvingLine(out,x,y,toolDiameterSquared,first,j<count-1);
			first = false;
		}
	}
	
	protected void parseDXFPolylineBackward(Writer out,DXFPolyline entity,double scale,double imageCenterX,double imageCenterY,double toolDiameterSquared) throws IOException {
		boolean first = true;
		int count = entity.getVertexCount() + (entity.isClosed()?1:0);
		for (int j = 0; j < count; ++j) {
			DXFVertex v = entity.getVertex((count+count-1-j) % entity.getVertexCount());
			double x = (v.getX() - imageCenterX) * scale;
			double y = (v.getY() - imageCenterY) * scale;
			parseCurvingLine(out,x,y,toolDiameterSquared,first,j<count-1);
			first = false;
		}
	}
	
	protected void parseCurvingLine(Writer out,double x,double y,double limitSquared,boolean first,boolean last) throws IOException {
		double dx = x - previousX;
		double dy = y - previousY;

		if (first == true) {
			if (dx * dx + dy * dy > limitSquared) {
				// line does not start at last tool location, lift and move.
				if (!lastUp) liftPen(out);
				moveTo(out, (float) x, (float) y,true);
			}
			// else line starts right here, pen is down, do nothing extra.
		} else {
			if (lastUp) lowerPen(out);
			// not the first point, draw.
			if (last && dx * dx + dy * dy < limitSquared)
				return; // points too close together
			moveTo(out, (float) x, (float) y,false);
		}
	}
	
	protected void parseDXFLine(Writer out,DXFLine entity,double scale,double imageCenterX,double imageCenterY,double toolDiameterSquared) throws IOException {
		Point start = entity.getStartPoint();
		Point end = entity.getEndPoint();

		double x = (start.getX() - imageCenterX) * scale;
		double y = (start.getY() - imageCenterY) * scale;
		double x2 = (end.getX() - imageCenterX) * scale;
		double y2 = (end.getY() - imageCenterY) * scale;

		// which end is closer to the previous (x,y) ?
		double dx = previousX - x;
		double dy = previousY - y;
		double dx2 = previousX - x2;
		double dy2 = previousY - y2;
		if ( dx * dx + dy * dy < dx2 * dx2 + dy2 * dy2 ) {
			parseDXFLineEnds(out,toolDiameterSquared,x,y,x2,y2);
		} else {
			parseDXFLineEnds(out,toolDiameterSquared,x2,y2,x,y);
		}
	}
	
	protected void parseDXFLineEnds(Writer out,double toolDiameterSquared,double x,double y,double x2,double y2) throws IOException {
		double dx = previousX - x;
		double dy = previousY - y;
		if (dx * dx + dy * dy > toolDiameterSquared) {
			if (!lastUp) liftPen(out);
			moveTo(out, (float) x, (float) y, true);
			if (lastUp) lowerPen(out);
		}
		moveTo(out, (float) x2, (float) y2, false);
	}
}
