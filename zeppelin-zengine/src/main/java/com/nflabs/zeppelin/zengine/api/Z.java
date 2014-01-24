package com.nflabs.zeppelin.zengine.api;


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.PumpStreamHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nflabs.zeppelin.conf.ZeppelinConfiguration.ConfVars;
import com.nflabs.zeppelin.driver.ZeppelinConnection;
import com.nflabs.zeppelin.driver.ZeppelinDriver;
import com.nflabs.zeppelin.driver.ZeppelinDriverException;
import com.nflabs.zeppelin.result.Result;
import com.nflabs.zeppelin.result.ResultDataException;
import com.nflabs.zeppelin.util.Util;
import com.nflabs.zeppelin.zengine.ParamInfo;
import com.nflabs.zeppelin.zengine.ZException;
import com.nflabs.zeppelin.zengine.Zengine;


/**
 * Z class is abstract class for Zeppelin Plan.
 * Instances of Z class can construct liked list by pipe method.
 * @author moon
 *
 *
 * Depends on: 
 *  - ScriptEngine (for ERB)
 *  - ZeppelinDriver 
 *  - ZeppelinConfiguration 
 *  
 */
public abstract class Z {
	String id; // z object identifier
	
	Z prev;
	transient Z next;
	
	private Result result;
	private Result lastQueryResult;
	boolean executed = false;
	int maxResult = 10000;
	boolean webEnabled = false;
	private String name;
	private boolean autogenName;   // if autogenerated
	private boolean table;
	Map<String, Object> params = new HashMap<String, Object>();
	private Map<String, ParamInfo> paramInfos;
	transient static final String NAME_PREFIX="zp_";

	//TODO(alex): get rid of it here as only Configuration is needed
	protected  Zengine zen;
    protected transient ZeppelinDriver driver;
    
	protected Z(Zengine z){
	    this.zen = z;
		this.id = Integer.toString(hashCode());
		this.maxResult = zen.getConf().getInt(ConfVars.ZEPPELIN_MAX_RESULT);
	}

	/**
	 * Get id string
	 * @return id string of this object
	 */
	public String getId(){
		return id;
	}
	
	private Logger logger(){
		return LoggerFactory.getLogger(Z.class);
	}
	

	/**
	 * Add a paramter to pass template environment
	 * @param key name of parameter
	 * @param val value of parameter
	 * @return this object
	 */
	public Z withParam(String key, Object val){
		params.put(key, val);
		return this;
	}
	
	public Z withParams(Map<String, Object> params){
		this.params.putAll(params);
		return this;
	}

	public Map<String, Object> getParams(){
		return params;
	}
	
	public void setParams(Map<String, Object> params){
		this.params = params;
	}
	
	/**
	 * Set max number of rows. Data returned by result() method will have maximum maxResult number of rows. default 10000
	 * @param maxResult maximum number of rows, result() method want to return
	 * @return this Z object
	 */
	public Z withMaxResult(int maxResult){
		this.maxResult = maxResult;
		return this;
	}

	/**
	 * Pipe another Z instance. Current Z instance will be input of given instance by parameter.
	 * @param z Z instance to be piped. 
	 * @return Piped Z instance. (the same with passed from parameter)
	 */
	public Z pipe(Z z){
		unPipe();
		if(isUnNamed()){
			name = NAME_PREFIX + this.hashCode();
			autogenName = true;
		}
		setNext(z);
		z.setPrev(this);
		return z;
	}
	
	/**
	 * Unlink pipe.
	 * @return this object
	 */
	public Z unPipe(){
		if(next()!=null){
			next().setPrev(null);
			setNext(null);
			
			if(autogenName==true){
				this.withName(null);
			}
		}
		return this;
	}
	
	/**
	 * Get previous Z instance linked by pipe.
	 * @return Previous Z instance. if there's no previous instance, return null
	 */
	public Z prev(){
		return prev;
	}
	public boolean hasPrev(){
	    return prev != null;
	}

	/**
	 * Get next Z instance linked by pipe
	 * @return Next Z instance. if there's no next instance, return null
	 */
	public Z next(){
		return next;
	}
	public boolean hasNext(){
	    return next != null;
	}

	
	/**
	 * Manually link previous Z instance. You should not use this method. use pipe() instead.
	 * Only for manually reconstructing Z plan linked list after deserialize.
	 *
	 * @param prev previous Z instance
	 */
	public void setPrev(Z prev){
		this.prev = prev;
	}

	/**
	 * Manually link next Z instance. You should not use this method. use pipe() instead.
	 * Only for manually reconstructing Z plan linked list after deserialize.
	 *
	 * @param next next Z instance
	 */

	public void setNext(Z next){
		this.next = next;
	}
	
	/**
	 * Get name. name can be null.
	 * name is table(view) name of result being saved
	 */
	public String name(){
		return name;
	}
	
	
	/**
	 * Set output table(view) name
	 * Execution of query will be saved in to this table(view)
	 * If name is null, out is not saved in the table(view).
	 * By default, name is automatically generated.
	 * @param name null if you don't want save the result into table(view). else the name of table(view) want to save
	 * @return
	 */
	public Z withName(String name){
		if(name==null){
			if(next()!=null){
				name = NAME_PREFIX + this.hashCode();
				autogenName = true;
			} else {
				autogenName = false;
			}
		}
		this.name = name;
		return this;
	}
	
	/**
	 * if name is set (by withName() method), execution result will be saved into table(view).
	 * this method controlls if table is used or view is used to save the result.
	 * by default view is used.
	 * @param table true for saving result into the table. false for saving result into view. default false
	 * @return
	 */
	public Z withTable(boolean table){
		this.table  = table;
		return this;
	}
	
	/**
	 * Check withTable setting
	 * @return
	 */
	public boolean isTable(){
		return table;
	}

	
	/**
	 * Get HiveQL compatible query to execute
	 * 
	 * @return HiveQL compatible query
	 * @throws ZException
	 */
	public abstract String getQuery() throws ZException;
	/**
	 * Resource files needed by query. (the query returned by getQuery())
	 * Hive uses 'add FILE' or 'add JAR' to add resource before execute query.
	 * Resources returned by this method will be automatically added.
	 * 
	 * @return list of URI of resources
	 * @throws ZException
	 */
	public abstract List<URI> getResources() throws ZException;
	
	/**
	 * Query to cleaning up this instance
	 * This query will be executed when it is being cleaned.
	 * @return HiveQL compatible query
	 * @throws ZException
	 */
	public abstract String getReleaseQuery() throws ZException;
	
	/**
	 * Get web resource of this Z instnace.
	 * This is gateway of visualization.
	 * 
	 * @param path resource path. 
	 * @return input stream of requested resource. or null if there's no such resource
	 * @throws ZException
	 */
	public abstract InputStream readWebResource(String path) throws ZException;
	
	/**
	 * Return if web is enabled.
	 * @return true if there's some resource can be returned by readWebResource().
	 *         false otherwise
	 */
	public abstract boolean isWebEnabled();
	
	protected abstract void initialize() throws ZException;
	
	/**
	 * Release all intermediate data(table/view) from this instance to head of linked(piped) z instance list
	 * @throws ZException
	 */
	public void release() throws ZException{
		initialize();
		if (!executed) return;
		
		try {
			if (name()!=null && autogenName==true){
				if (isTable()){
					driver.dropTable(name());
				} else {
					driver.dropView(name());
				}
			}
		} catch (ZeppelinDriverException e){
			throw new ZException(e);
		}
		
		String q = getReleaseQuery();
		if (q!=null){
			executeQuery(q, maxResult);
		}
		
		if (hasPrev()){
			prev().release();
		}
	}
	
	/**
	 * Execute Z instance's query from head of this linked(piped) list to this instance.
	 * @return this object
	 * @throws ZException
	 */
	public Z execute() throws ZException {
		if(executed) { return this; }
		initialize();
		//connection = connection(conn);
		
		if (this.hasPrev()){
			prev().execute();
		}
		String q;
		String query = getQuery();
		String[] queries = Util.split(query, ';');
		for (int i=0; i<queries.length-1; i++){//all except last one
		    q = queries[i];
		    lastQueryResult = executeQuery(q, maxResult);
		}
		
		if (queries.length > 0) {//the last query
            q = queries[queries.length-1];
            if (isUnNamed()){
                lastQueryResult = executeQuery(q, maxResult);
            } else {
                if(!isSaveableQuery(q)){
                    throw new ZException("Can not save query "+q+" into table "+name());
                }
                if(isTable()){
                    lastQueryResult = driver.createTableFromQuery(name(), q);
                } else {
                    lastQueryResult = driver.createTableFromQuery(name(), q);
                }
            }

		}

		webEnabled = isWebEnabled();
		executed = true;
		return this;
	}
	
	/**
	 * @return 
	 * @throws ZException 
	 * 
	 */
	public Z dryRun() throws ZException{
		initialize();
		
		if(hasPrev()){
			prev().dryRun();
		}
		Map<String, ParamInfo> infos = extractParams();

		this.paramInfos = infos;
		return this;
	}
	
	protected abstract Map<String, ParamInfo> extractParams() throws ZException;
	
	public Map<String, ParamInfo> getParamInfos(){
		return paramInfos;
	}
	
	private boolean isSaveableQuery(String query){
		if (query==null || query.isEmpty()) return false;
		String q = query.trim().toLowerCase();
		return q.startsWith("select") || q.startsWith("map");		   
	}
		
	/**
	 * Get result of execution
	 * If there's name() defined, first it'll looking for table(or view) with name().
	 * And if table(or view) with name() exists, then read data from it and return as a result.
	 * 
	 * When name() is undefined(null), last query executed by execute() method will be the result.
	 * 
	 * @return result
	 * @throws ZException 
	 */
	public Result result() throws ZException{
		if(!executed){
			throw new ZException("Can not get result because of this is not executed");
		}

		if(result==null){
			if(isUnNamed()){
				if(lastQueryResult!=null){
					result = lastQueryResult;
				} else {
					if(prev()!=null){
						result = prev().result();
					}
				}
			} else { // named
				try{
					result = driver.select(name(), maxResult);
				} catch(Exception e){  // if table not found
					if(lastQueryResult!=null){
						result = lastQueryResult;
					}
				}
			}
		}
		
		return result;
	}

    private boolean isUnNamed() {
        return name==null;
    }
	
	/**
	 * Check if this instance is executed or not
	 * @return true if executed.
	 *         false if not executed
	 */
	public boolean isExecuted(){
		return executed;
	}
	
	
	private Result shellCommand(String cmd) throws ExecuteException, IOException, ResultDataException{
		logger().info("Run shell command '"+cmd+"'");
		CommandLine cmdLine = CommandLine.parse("bash");
		cmdLine.addArgument("-c", false);
		cmdLine.addArgument(cmd, false);
		DefaultExecutor executor = new DefaultExecutor();
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		executor.setStreamHandler(new PumpStreamHandler(outputStream));

		executor.setWatchdog(new ExecuteWatchdog(zen.getConf().getLong(ConfVars.ZEPPELIN_COMMAND_TIMEOUT)));
		int exitValue = executor.execute(cmdLine);
		String [] msgs = null;
		if (outputStream!=null){
			String msgstr = outputStream.toString();
			logger().debug("exec out="+msgstr);
			if(msgstr!=null){
				msgs = msgstr.split("\n");
			}
		}
		Result resultData = new Result(exitValue, msgs);
		return resultData;		
	}
	
	private Result executeQuery(String query, int max) throws ZException{
		initialize();
		if(query==null) return null;
		
		if(query.startsWith("!")){
            String cmd = query.substring(1);
			try {
				return shellCommand(cmd);
			} catch (ExecuteException e) {
				logger().error("Failed to execute shell command " + cmd, e);
			} catch (IOException e) {
				logger().error("Failed to execute shell command " + cmd, e);
			} catch (ResultDataException e) {
				logger().error("Failed to execute shell command " + cmd, e);
			}
		}
		
        // add resources
        List<URI> resources = getResources();

        for (URI res : resources) {
            driver.addResource(res);
        }

        // execute query
        logger().info("executeQuery : " + query);
        return driver.query(query);
	}

	public void setDriver(ZeppelinDriver driver){
		this.driver = driver;
	}

	/*for tests*/ ZeppelinDriver getDriver() {
	    return this.driver;
	}

	
    public boolean abort() {
        try {
            this.driver.abort();
        } catch (ZeppelinDriverException e) {
            logger().error("Abort failure", e);
            return false;
        }
        return true;
    }
}
