package org.hl7.fhir.dstu21.utils;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hl7.fhir.utilities.Utilities;
import org.apache.commons.lang3.NotImplementedException;
import org.hl7.fhir.exceptions.DefinitionException;
import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.dstu21.model.Bundle;
import org.hl7.fhir.dstu21.model.CodeableConcept;
import org.hl7.fhir.dstu21.model.Element;
import org.hl7.fhir.dstu21.model.ElementDefinition;
import org.hl7.fhir.dstu21.model.Extension;
import org.hl7.fhir.dstu21.model.Resource;
import org.hl7.fhir.dstu21.model.StringType;
import org.hl7.fhir.dstu21.model.StructureDefinition;
import org.hl7.fhir.dstu21.model.Timing;
import org.hl7.fhir.dstu21.model.Type;
import org.hl7.fhir.dstu21.model.Bundle.BundleType;
import org.hl7.fhir.dstu21.model.ElementDefinition.ElementDefinitionMappingComponent;
import org.hl7.fhir.dstu21.model.StructureDefinition.StructureDefinitionMappingComponent;
import org.hl7.fhir.dstu21.utils.FHIRPathEngine.BundleMappingContext;
import org.hl7.fhir.dstu21.utils.FHIRPathEngine.IConstantResolver;
import org.hl7.fhir.dstu21.utils.FHIRPathEngine.MapExpression;
import org.hl7.fhir.dstu21.utils.FHIRPathEngine.MappingContext;
import org.hl7.fhir.dstu21.utils.FHIRPathEngine.ResourceFactory;

public class LogicalModelUtilities implements IConstantResolver {

  private IWorkerContext context;
  

  public LogicalModelUtilities(IWorkerContext context) {
    super();
    this.context = context;
  }

  /**
   * Given a logical model, with a Logical Mapping column, 
   * generate profiles consistent with it 
   * 
   * single threaded only
   * 
   * @param logical
   * @return
   */
  public List<StructureDefinition> generateProfiles(StructureDefinition logical) {
    return null;
  }
  
  private Map<String, String> codings = new HashMap<String, String>();
  
  // start at 0, and keep calling this with index++ until it returns null
  public Bundle generateExample(StructureDefinition logical, int index) throws Exception {
    processMappings(logical);
    
    // 1. process the data
    LogicalModelMode data = readExampleData(logical.getSnapshot().getElement(), index);
    if (data.isEmpty())
      return null;
    
    // 2. parse the expressions
    String key = getMappingKey(logical);

    Bundle bnd = new Bundle();
    bnd.setType(BundleType.COLLECTION);
    bnd.setId(UUID.randomUUID().toString().toLowerCase());
    bnd.getMeta().setLastUpdated(new Date());
    
    FHIRPathEngine fp = new FHIRPathEngine(context);
    fp.setConstantResolver(this);
    FHIRPathEngine.BundleMappingContext mc = new FHIRPathEngine.BundleMappingContext(bnd); 
    fp.initMapping(mc);
    
    // first, look for data
    parseExpressions(fp, mc, key, data);
//    executeExpressions(fp, mc, data);
    return bnd; 
  }

  private void processMappings(StructureDefinition logical) {
    for (StructureDefinitionMappingComponent m : logical.getMapping()) {
      if (isCodingSystem(m.getUri()))
        codings.put(m.getIdentity(), m.getUri());
    }
  }

  private boolean isCodingSystem(String uri) {
    return uri.equals("http://snomed.info/sct") || uri.equals("http://loinc.org") || uri.equals("http://cap.org/ecc");
  }

  private LogicalModelMode readExampleData(List<ElementDefinition> element, int index) throws Exception {
    Map<String, LogicalModelMode> map = new HashMap<String, LogicalModelMode>();
    LogicalModelMode root = null;
    for (ElementDefinition ed : element) {
      LogicalModelMode focus;
      if (root == null) { // first
        focus = new LogicalModelMode(ed);
        root = focus;
      } else {
        focus = new LogicalModelMode(ed);
        LogicalModelMode parent = map.get(ed.getPath().substring(0, ed.getPath().lastIndexOf(".")));
        if (parent.hasData())
          throw new DefinitionException("Cannot provide example data for elements that have children on "+parent.getDefinition().getPath());
        parent.getChildren().add(focus);
      }
      map.put(ed.getPath(), focus);
      Type data = getExample(ed, index);
      if (data != null)
        focus.setData(data);
    }
    return root;
  }

  private Type getExample(ElementDefinition ed, int index) {
    if (index == 0)
      return ed.getExample();
    else 
      for (Extension ex : ed.getExtension()) {
        if (ex.getUrl().equals("http://hl7.org/fhir/StructureDefinition/structuredefinition-example")) {
          String i = ((StringType) ToolingExtensions.getExtension(ex, "index").getValue()).getValue();
          if (Integer.parseInt(i) == index) {
            return ToolingExtensions.getExtension(ex, "exValue").getValue();
          }
        }
      }
    return null;
  }

  private void parseExpressions(FHIRPathEngine fp, BundleMappingContext mc, String key, LogicalModelMode node) throws Exception {
    node.setMapping(getLogicalMapping(node.getDefinition(), key));
    if (node.getMapping() != null && !node.getMapping().startsWith("!"))
      node.setExpressions(fp.parseMap(mc, node.getMapping()));
    if (node.hasChildren())
      for (LogicalModelMode child : node.getChildren()) 
        parseExpressions(fp, mc, key, child);
  }

  private String getMappingKey(StructureDefinition logical) throws Exception {
    for (StructureDefinitionMappingComponent m : logical.getMapping()) {
      if (m.getUri().equals("http://hl7.org/fhir/logical"))
        return m.getIdentity();
    }
    throw new DefinitionException("unable to find logical mappings");
  }

  private String getLogicalMapping(ElementDefinition ed, String key) {
    for (ElementDefinitionMappingComponent m : ed.getMapping()) {
      if (m.getIdentity().equals(key))
        return m.getMap();
    }
    return null;
  }

  private void executeExpressions(FHIRPathEngine fp, BundleMappingContext mc, LogicalModelMode node) throws Exception {
    if (node.isEmpty())
      return; // don't execute expressions if there's no data
    if (node.getExpressions() != null) {
      try {
        fp.performMapping(mc, node.getDefinition(), node.getData(), node.getExpressions()); // it's ok if node.getData() == null
      } catch (Exception e) {
        throw new FHIRException("Error running mapping '"+node.getMapping()+"': "+e.getMessage(), e);
      }
    }
    if (node.hasChildren())
      for (LogicalModelMode child : node.getChildren()) 
        executeExpressions(fp, mc, child);
  }        

  @Override
  public Type resolveConstant(Object appContext, String name) {
    ElementDefinition ed = (ElementDefinition) appContext;
    if (name.equals("%map-codes")) {
      CodeableConcept cc = new CodeableConcept();
      cc.setText(ed.getShort());
      for (ElementDefinitionMappingComponent m : ed.getMapping()) {
        if (codings.containsKey(m.getIdentity())) {
          cc.addCoding().setSystem(codings.get(m.getIdentity())).setCode(m.getMap());
        }
      }
      return cc;
    }
    else
      throw new NotImplementedException("Not done yet ("+name+")");
  }

  @Override
  public String resolveConstantType(Object appContext, String name) {
    if (name.equals("%map-codes"))
      return "CodeableConcept";
    else
      throw new NotImplementedException("Not done yet ("+name+")");
  }
  
}
