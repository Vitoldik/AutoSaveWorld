/**
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 *
 */

package autosaveworld.utils.codeinvoker;

import java.io.File;
import java.io.FileNotFoundException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Scanner;

import autosaveworld.core.GlobalConstants;
import autosaveworld.utils.codeinvoker.ConstructParser.ConstructInfo;
import autosaveworld.utils.codeinvoker.GetParser.GetInfo;
import autosaveworld.utils.codeinvoker.IfParser.IfInfo;
import autosaveworld.utils.codeinvoker.InvokeParser.InvokeInfo;
import autosaveworld.utils.codeinvoker.SetParser.SetInfo;

public class CodeInvoker {

	private CodeContext context = new CodeContext();
	private IfParser ifparser = new IfParser(context);
	private InvokeParser iparser = new InvokeParser(context);
	private ConstructParser cparser = new ConstructParser(context);
	private GetParser gparser = new GetParser(context);
	private SetParser sparser = new SetParser(context);

	// array of objects - (STRING:something - string, INTEGER:something - integer, and so on for primitives, CLASS:classname - class object, CONTEXT:name - codeContext object, LAST - last returned object, NULL - null, anything else - Object), separated by |, to use | character use {VERTBAR}, to use space use {SPACE}

	// nop - does nothing
	// return params - quits code invling and returns object(only first object from params is used)
	// store name - stores last returned object
	// remove name - removes object from memory
	// construct classname,params - constructs object
	// invoke classname,methodname,classobject,object ot classname,params - invokes method (use  or empty string as classobject if return type doesn't matters)
	// get classname,fieldname,object or classname - gets field value
	// set classname,fieldname,object or classname,params - sets field value (only first object from params is used);
	// print params
	// if index,index,params - goes to first index code if all the params equals true or to second if not
	// goto index - goes to line of code
	// where:
	// name - codeContext variable name
	// methodname - method name
	// fieldname - field name
	// classname - classname to apply get/set/invoke on (for use with static methods/fields), to use classname instead of object use CNAME:classname
	// object - object to apply get/set/invoke on
	// params - array of objects
	// classobject - class object

	// example script
	// (print server online mode)
	// invoke getOnlineMode,,CNAME:org.bukkit.Bukkit
	// print LAST
	// (print offline player _Shevchik_ uuid)
	// invoke getOfflinePlayer,CLASS:org.bukkit.OfflinePlayer,CNAME:org.bukkit.Bukkit,String:_Shevchik_
	// invoke getUniqueId,CLASS:java.util.UUID,LAST
	// print LAST
	// (change server max players count to 201(works on 1.7.10))
	// invoke getServer,CLASS:org.bukkit.Server,CNAME:org.bukkit.Bukkit
	// get playerList,LAST
	// set maxPlayers,LAST,INTEGER:201
	// (give all online players 5$(uses vault))
	// invoke getServicesManager,,CNAME:org.bukkit.Bukkit
	// invoke getRegistration,,LAST,CLASS:net.milkbowl.vault.economy.Economy
	// invoke getProvider,,LAST
	// store vault
	// invoke getOnlinePlayers,CLASS:java.util.Collection,CNAME:org.bukkit.Bukkit
	// invoke iterator,,LAST
	// store iterator
	// invoke hasNext,,Context:iterator
	// if 9,12,LAST
	// invoke next,,Context:iterator
	// invoke depositPlayer,,CONTEXT:vault,LAST|DOUBLE:5
	// goto 7

	public Object invokeCode(String filename) {
		File file = new File(GlobalConstants.getAutoSaveWorldFolder() + "scripts" + File.separator + filename + ".yml");
		return invokeCode(readCommandsFromFile(file).toArray(new String[0]));
	}

	public Object invokeCode(String[] commands) {
		int line = -1;
		try {
			for (line = 0; line < commands.length; line++) {
				String command = commands[line];
				String[] split = command.split("\\s+");
				CodeCommand codecommand = CodeCommand.valueOf(split[0].toUpperCase());
				switch (codecommand) {
					case NOP: {
						continue;
					}
					case RETURN: {
						return context.getObjects(split[1])[0];
					}
					case STORE: {
						context.objectsrefs.put(split[1], context.returnedobject);
						continue;
					}
					case REMOVE: {
						context.objectsrefs.remove(split[1]);
						continue;
					}
					case IF: {
						IfInfo ifinfo = ifparser.getIfInfo(split[1]);
						boolean result = true;
						for (Object obj : ifinfo.getObjects()) {
							if (!obj.equals(true)) {
								result = false;
							}
						}
						if (result) {
							line = ifinfo.getEIndex() - 1;
						} else {
							line = ifinfo.getNEIndex() - 1;
						}
						continue;
					}
					case GOTO: {
						line = Integer.parseInt(split[1]) - 1;
						continue;
					}
					case CONSTRUCT: {
						ConstructInfo cinfo = cparser.getConstructInfo(split[1]);
						for (Constructor<?> constr : context.usedclass.getDeclaredConstructors()) {
							boolean found = true;
							for (int i = 0; i < constr.getParameterTypes().length; i++) {
								if (constr.getParameterTypes()[i] != cinfo.getObjects()[i].getClass()) {
									found = false;
								}
							}
							if (!found) {
								continue;
							}
							constr.setAccessible(true);
							Object obj;
							if (cinfo.getObjects() == null) {
								obj = constr.newInstance();
							} else {
								obj = constr.newInstance(cinfo.getObjects());
							}
							context.returnedobject = obj;
						}
						continue;
					}
					case INVOKE: {
						InvokeInfo iinfo = iparser.getInvokeInfo(split[1]);
						Method method = findMethod(getAllMethods(iinfo.getObject() == null ? context.usedclass : iinfo.getObject().getClass()), iinfo.getMethodName(), iinfo.getReturnType(), iinfo.getObjects());
						method.setAccessible(true);
						Object obj;
						if (iinfo.getObjects() == null) {
							obj = method.invoke(iinfo.getObject());
						} else {
							obj = method.invoke(iinfo.getObject(), iinfo.getObjects());
						}
						if (method.getReturnType() != void.class) {
							context.returnedobject = obj;
						}
						continue;
					}
					case GET: {
						GetInfo ginfo = gparser.getGetInfo(split[1]);
						Field field = findField(getAllFields(ginfo.getObject() == null ? context.usedclass : ginfo.getObject().getClass()), ginfo.getFieldName());
						field.setAccessible(true);
						context.returnedobject = field.get(ginfo.getObject());
						continue;
					}
					case SET: {
						SetInfo sinfo = sparser.getSetInfo(split[1]);
						Field field = findField(getAllFields(sinfo.getObject() == null ? context.usedclass : sinfo.getObject().getClass()), sinfo.getFieldName());
						field.setAccessible(true);
						field.set(sinfo.getObject(), sinfo.getNew());
						continue;
					}
					case PRINT: {
						for (Object obj : context.getObjects(split[1])) {
							System.out.println(obj);
						}
						continue;
					}
					default: {
						break;
					}
				}
			}
		} catch (Throwable t) {
			System.err.println("Error happened while invoking line "+line);
			t.printStackTrace();
		}
		return new EmptyReturn();
	}

	private Method findMethod(Method[] allmethods, String methodname, Class<?> returntype, Object[] params) {
		for (Method method : allmethods) {
			if (!method.getName().equals(methodname)) {
				continue;
			}
			if ((returntype != null) && !returntype.isAssignableFrom(method.getReturnType())) {
				continue;
			}
			if (!isSameParams(method.getParameterTypes(), params)) {
				continue;
			}
			return method;
		}
		throw new RuntimeException("Can't find method " + methodname);
	}

	private boolean isSameParams(Class<?>[] methodParams, Object[] params) {
		if (params == null) {
			if (methodParams.length == 0) {
				return true;
			} else {
				return false;
			}
		}
		if (methodParams.length != params.length) {
			return false;
		}
		for (int i = 0; i < methodParams.length; i++) {
			if (methodParams[i].isArray()) {
				if (params[i].getClass().isArray()) {
					if (!methodParams[i].getComponentType().isAssignableFrom(params[i].getClass().getComponentType())) {
						return false;
					}
				} else {
					return false;
				}
			} else if (methodParams[i].isPrimitive()) {
				if (methodParams[i] == boolean.class) {
					if (params[i].getClass() != Boolean.class) {
						return false;
					}
				} else if (!Number.class.isAssignableFrom(params[i].getClass())) {
					return false;
				}
			} else if (!methodParams[i].isAssignableFrom(params[i].getClass())) {
				return false;
			}
		}
		return true;
	}

	private Method[] getAllMethods(Class<?> clazz) {
		LinkedList<Method> methods = new LinkedList<Method>();
		do {
			methods.addAll(Arrays.asList(clazz.getDeclaredMethods()));
		} while ((clazz = clazz.getSuperclass()) != null);
		return methods.toArray(new Method[methods.size()]);
	}

	private Field findField(Field[] allfields, String fieldname) {
		for (Field field : allfields) {
			if (field.getName().equals(fieldname)) {
				return field;
			}
		}
		throw new RuntimeException("Can't find field " + fieldname);
	}

	private Field[] getAllFields(Class<?> clazz) {
		LinkedList<Field> fields = new LinkedList<Field>();
		do {
			fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
		} while ((clazz = clazz.getSuperclass()) != null);
		return fields.toArray(new Field[fields.size()]);
	}

	private enum CodeCommand {
		NOP, STORE, REMOVE, IF, GOTO, CONSTRUCT, INVOKE, GET, SET, PRINT, RETURN
	}

	private List<String> readCommandsFromFile(File file) {
		List<String> commands = new LinkedList<String>();
		try (Scanner scan = new Scanner(file)) {
			while (scan.hasNextLine()) {
				commands.add(scan.nextLine());
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
		return commands;
	}

	public static class EmptyReturn {
	}

}
