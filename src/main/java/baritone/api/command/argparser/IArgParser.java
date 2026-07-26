package baritone.api.command.argparser;

import baritone.api.command.argument.ICommandArgument;

public interface IArgParser<T> {
    Class<T> getTarget();
    interface Stateless<T> extends IArgParser<T> {
        T parseArg(ICommandArgument argument) throws Exception;
    }
    interface Stated<T, S> extends IArgParser<T> {
        Class<S> getStateType();
        T parseArg(ICommandArgument argument, S state) throws Exception;
    }
}
