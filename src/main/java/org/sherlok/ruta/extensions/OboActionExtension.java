package org.sherlok.ruta.extensions;

import java.util.List;

import org.apache.uima.cas.Type;
import org.apache.uima.ruta.RutaElement;
import org.apache.uima.ruta.action.AbstractRutaAction;
import org.apache.uima.ruta.expression.RutaExpression;
import org.apache.uima.ruta.expression.string.ComposedStringExpression;
import org.apache.uima.ruta.expression.type.SimpleTypeExpression;
import org.apache.uima.ruta.extensions.IRutaActionExtension;
import org.apache.uima.ruta.extensions.RutaParseException;
import org.apache.uima.ruta.verbalize.RutaVerbalizer;

public class OboActionExtension implements IRutaActionExtension {

    public final static String EXTENSION_KEYWORD = "OBO";

    private final Class<?>[] extensions = new Class[] { OboAction.class };

    public String verbalize(RutaElement element, RutaVerbalizer verbalizer) {
        if (element instanceof OboAction) {
            return verbalizeName(element)
                    + "("
                    + verbalizer.verbalizeExpressionList(((OboAction) element)
                            .getIndexExprList()) + ")";
        } else {
            return "UnknownAction";
        }
    }

    @SuppressWarnings("unused")
    public AbstractRutaAction createAction(String name,
            List<RutaExpression> args) throws RutaParseException {

        if (args == null || args.size() != 2) {
            throw new RutaParseException(
                    "OBO acccepts as arguments AnnotationClass OboFile");
        } else {

            RutaExpression re = args.get(0);
            String c = re.getClass().getName();

            SimpleTypeExpression te = (SimpleTypeExpression) re;
            Type type = te.getType(null);
            String annotationClass = te.getTypeString();

            re = args.get(1);
            ComposedStringExpression ce = (ComposedStringExpression) re;
            String oboFile = ce.getExpressions().get(0).toString();

            // FIXME validate
            return new OboAction(null);
        }
    }

    public String verbalizeName(RutaElement element) {
        return EXTENSION_KEYWORD;
    }

    public String[] getKnownExtensions() {
        return new String[] { EXTENSION_KEYWORD };
    }

    public Class<?>[] extensions() {
        return extensions;
    }
}
