import type { Step } from "@stepperize/react";
import { StepperActionType, StepperContext, type WizardTopProps } from "./types";
import React, { useEffect } from "react";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { cn } from "@/lib/utils";
export function StepperTopNav<TSteps extends readonly Step[]>({ activeStepRef, useStepperHook, utils, ...props }: WizardTopProps & React.ComponentProps<"div">) {
    const { state: stepperState, setState: setStepperState } = React.useContext(StepperContext)!;
    const stepper = useStepperHook();
    const currentIndex = utils.getIndex(stepper.current.id);
    const goTo = (stepId: TSteps[number]["id"]) => {
        activeStepRef.current?.saveState();
        setStepperState({
            type: StepperActionType.SetStepValidity, payload: { stepId: stepper.current.id, isValid: activeStepRef.current?.isValid() || false }
        });
        stepper.goTo(stepId);
    };
    const invalidMarker = (stepId: TSteps[number]["id"]) => {
        const metadata = stepperState.steps[stepId as string];
        const className = metadata?.isValid === false ? "border-2 border-red-500" : "";
       // console.log("stepId:", stepId, " isValid:", metadata?.isValid, " className:", className);
        return className;
    };
    return <>
        <div className={cn("flex flex-row", props.className)} {...props}>
            <nav className="group" >
                <ol
                    className="flex items-center justify-between gap-2">
                    {stepper.all.map((step, index, array) => (
                        <React.Fragment key={step.id}>
                            <li className="flex items-center gap-4 flex-shrink-0">
                                <Button
                                    disabled={stepperState.isBusy}
                                    type="button"
                                    role="tab"
                                    variant={index <= currentIndex ? 'default' : 'outline'}
                                    className={cn("flex size-6 items-center justify-center rounded-full p-2", invalidMarker(step.id))}
                                    onClick={() => goTo(step.id)}
                                >
                                    {index + 1}
                                </Button>
                                <span className="text-sm font-medium">{step.title}</span>
                            </li>
                            {index < array.length - 1 && (
                                <Separator
                                    className={`flex-1 ${index < currentIndex ? 'bg-primary' : 'bg-muted'
                                        }`}
                                />
                            )}
                        </React.Fragment>
                    ))}
                </ol>
            </nav>
        </div>
    </>
}
