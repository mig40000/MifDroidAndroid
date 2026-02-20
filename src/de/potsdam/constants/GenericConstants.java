/**
 * 
 */
package de.potsdam.constants;


/**
 * @author abhishektiwari
 *
 */
public class GenericConstants {

	public static final int MAX_CLASS = 20000;
	public static final int MAX_FOLDERS = 2000;
	public static final String DEFAULT_LOG_DIRECTORY = "output/logs/";
	public static final String APKTOOL_OUTPUT_DIRECTORY = "output/intermediate/";
	public static final String DB_NAME = "jdbc:sqlite:Database/Intent.sqlite";
	
	public final String class_init = "Ljava/lang/Class;->forName(Ljava/lang/String;)Ljava/lang/Class;";
	public static final String LOAD_URL ="Landroid/webkit/WebView;->loadUrl(Ljava/lang/String;)V";
	public static final String STRINGBUILDER_INIT = "Ljava/lang/StringBuilder;-><init>()V";
	public static final String STRINGBUILDER_APPEND = "Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;";
	public static final String STRINGBUILDER_TOSTRING = "Ljava/lang/StringBuilder;->toString()Ljava/lang/String;";

	public static final String EVALUATE_JS = "Landroid/webkit/WebView;->evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V";
	public static final String ADDJSInterface = "Landroid/webkit/WebView;->addJavascriptInterface(Ljava/lang/Object;Ljava/lang/String;)V";
	
}
