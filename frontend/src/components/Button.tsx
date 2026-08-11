import type { ButtonHTMLAttributes, ReactNode } from "react";

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  icon?: ReactNode;
}

export function Button({ children, icon, className, ...props }: ButtonProps) {
  return (
    <button className={`button${className ? ` ${className}` : ""}`} type="button" {...props}>
      {icon}
      <span>{children}</span>
    </button>
  );
}
